# What the SITL scripts share (dot-sourced by build.ps1, start.ps1, stop.ps1, status.ps1). Betaflight as a program
# runs inside WSL (Ubuntu), as root: tools\sitl\sitl.sh does the work there; these are the way in from Windows.
$SitlDistro = $(if ($env:DRAUPNE_SITL_DISTRO) { $env:DRAUPNE_SITL_DISTRO } else { 'Ubuntu' })
# Betaflight's own numbers, fixed in its source: UART n listens on TCP 5760 + n (UART1, MSP, on 5761)
$SitlMspPort = 5761
# the forwarder in WSL (sitl.sh, forwarder.py) listens 10000 above each UART it serves (UART1, 2, 4) and stays while
# Betaflight restarts: WSL's forwarding of 127.0.0.1 lost SITL's own port after a restart now and then (2026-11-23)
$SitlForwardPort = $SitlMspPort + $(if ($env:DRAUPNE_SITL_FORWARD_OFFSET) { [int]$env:DRAUPNE_SITL_FORWARD_OFFSET } else { 10000 })
$SitlVersions = @('4.4.1', '4.5.5', '2025.12.5', '4.3.2')
$SitlRoot = Join-Path $env:LOCALAPPDATA 'DraupneBench\sitl'

# C:\Users\x\folder -> /mnt/c/Users/x/folder (WSL's way to Windows' drives)
function ConvertTo-WslPath([string]$path) {
    $full = [IO.Path]::GetFullPath($path)
    if ($full -notmatch '^([A-Za-z]):\\(.*)$') { throw "Not a path on a drive: $path" }
    return '/mnt/' + $matches[1].ToLower() + '/' + ($matches[2] -replace '\\', '/')
}

$SitlScript = ConvertTo-WslPath (Join-Path $PSScriptRoot 'sitl.sh')

function Test-SitlWsl {
    try {
        $names = (& wsl.exe -l -q 2>$null) -join ' '
        return ($LASTEXITCODE -eq 0 -and ($names -replace "`0", '') -match "\b$SitlDistro\b")
    } catch {
        return $false
    }
}

# sitl.sh with these words, in WSL as root; its lines are written as they come, its exit code returned
function Invoke-SitlScript([string[]]$words) {
    # (Windows PowerShell makes each stderr line of a program an error of its own: here they are lines like the others)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & wsl.exe -d $SitlDistro -u root -- bash $SitlScript @words 2>&1 | ForEach-Object { Write-Host "$_" }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
}

# MSP_API_VERSION asked on 127.0.0.1:<port>: "1.45" when Betaflight answers, $null when nothing does
function Get-SitlApi([int]$port = $SitlMspPort, [int]$timeoutMs = 1500) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $c.BeginConnect('127.0.0.1', $port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne($timeoutMs) -or -not $c.Connected) { return $null }
        $c.EndConnect($connect)
        $s = $c.GetStream()
        $s.ReadTimeout = $timeoutMs
        $ask = [byte[]](0x24, 0x4D, 0x3C, 0x00, 0x01, 0x01)   # $M< size 0, MSP_API_VERSION (1), checksum 1
        $s.Write($ask, 0, $ask.Length)
        $got = New-Object System.Collections.Generic.List[byte]
        $buf = New-Object byte[] 64
        $until = (Get-Date).AddMilliseconds($timeoutMs)
        while ((Get-Date) -lt $until) {
            try { $n = $s.Read($buf, 0, $buf.Length) } catch { return $null }
            if ($n -le 0) { return $null }
            $got.AddRange([byte[]]$buf[0..($n - 1)])
            $a = $got.ToArray()
            for ($i = 0; $i + 8 -lt $a.Length; $i++) {
                # $M> 3 1 <protocol> <major> <minor> <checksum>
                if ($a[$i] -eq 0x24 -and $a[$i + 1] -eq 0x4D -and $a[$i + 2] -eq 0x3E -and $a[$i + 3] -eq 3 -and $a[$i + 4] -eq 1) {
                    return "$($a[$i + 6]).$($a[$i + 7])"
                }
            }
        }
        return $null
    } catch {
        return $null
    } finally {
        $c.Close()
    }
}
