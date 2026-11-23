<#
Betaflight as a program (SITL) started inside WSL; returns once it answers MSP on 127.0.0.1:5761 (WSL's own
localhost forwarding brings Windows there), and writes the run's folder out.

  tools\sitl\start.ps1 -Version 4.4.1                         a fresh board: Betaflight's defaults, a folder of its own
  tools\sitl\start.ps1 -Version 4.4.1 -Run <folder>           in that folder (its eeprom.bin is kept: a board as it was left)
  tools\sitl\start.ps1 -Version 4.4.1 -Eeprom <file>          that eeprom.bin copied in first (a board's saved settings)
  tools\sitl\start.ps1 -Version 4.4.1 -Feed                   with the level-attitude feeder beside it (feeder.py)

The folder (default %LOCALAPPDATA%\DraupneBench\sitl\<version>-<time>) holds the board's eeprom.bin, sitl.log (what
Betaflight says, with the time), starts.txt (how often it was started: save, the CLI's exit and MSP's reboot all end
the program, and it is started again in the same folder at once). Nothing is deleted. One SITL at a time; started
again with the same version and folder it says so and returns.
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Run = '',
    [string]$Eeprom = '',
    [int]$Port = 5761,
    [int]$TimeoutSec = 30,
    [switch]$Feed
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not (Test-SitlWsl)) { throw "No WSL distribution '$SitlDistro' on this PC (docs/TESTING.md, 'Betaflight as a program')." }
if ($Port -ne $SitlMspPort) { throw "Betaflight's SITL has UART1 on TCP $SitlMspPort, fixed in its source: -Port $Port cannot be had." }
if (-not $Run) { $Run = Join-Path $SitlRoot ($Version + '-' + (Get-Date).ToString('yyyyMMdd-HHmmss')) }
$Run = [IO.Path]::GetFullPath($Run)
$wslRun = ConvertTo-WslPath $Run

# one at a time: the same one again is no error
$now = (& wsl.exe -d $SitlDistro -u root -- bash $SitlScript status) -join ' '
if ($now -match '^running: Betaflight (\S+) pid \d+, folder (.+?), starts') {
    if ($matches[1] -eq $Version -and $matches[2] -eq $wslRun -and (Get-SitlApi $Port)) {
        Write-Host "Betaflight $Version runs already in $Run and answers MSP on 127.0.0.1:$Port."
        Write-Output $Run
        return
    }
    throw "Another SITL runs ($now): tools\sitl\stop.ps1 first."
}
if (Get-SitlApi $Port 500) { throw "Something answers MSP on 127.0.0.1:$Port already, and it is no SITL of these scripts: not started." }

New-Item -ItemType Directory -Force $Run | Out-Null
if ($Eeprom) {
    if (-not (Test-Path $Eeprom)) { throw "No eeprom file $Eeprom" }
    Copy-Item -LiteralPath $Eeprom -Destination (Join-Path $Run 'eeprom.bin') -Force
}
$words = @('-d', $SitlDistro, '-u', 'root', '--', 'bash', $SitlScript, 'run', $Version, $wslRun)
if ($Feed) { $words += 'feed' }
# the wsl.exe that runs the supervisor is what keeps WSL up: hidden, and left running until stop.ps1
$p = Start-Process -FilePath 'wsl.exe' -ArgumentList $words -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $Run 'supervisor.out') -RedirectStandardError (Join-Path $Run 'supervisor.err')
Set-Content -Path (Join-Path $Run 'wsl.pid') -Value $p.Id -Encoding ascii
$until = (Get-Date).AddSeconds($TimeoutSec)
# Betaflight answering through its forwarder (sitl.sh, forwarder.py) - the bench connects there, and the forwarder's
# port is there before Betaflight's: WSL's forwarding of 127.0.0.1 took up SITL's own new port late or not at all
# now and then (2025.12.5, 2026-11-23: no connection reached it on 5761 for 30 s; started again it answered at once)
$fwdApi = $null
while ((Get-Date) -lt $until -and -not $p.HasExited) {
    $fwdApi = Get-SitlApi $SitlForwardPort
    if ($fwdApi) { break }
    Start-Sleep -Milliseconds 300
}
if (-not $fwdApi) {
    $why = @(Get-Content (Join-Path $Run 'supervisor.err') -ErrorAction SilentlyContinue) + @(Get-Content (Join-Path $Run 'sitl.log') -Tail 8 -ErrorAction SilentlyContinue)
    if (-not $p.HasExited) { & (Join-Path $PSScriptRoot 'stop.ps1') | Out-Null }
    throw ("Betaflight $Version did not answer MSP through its forwarder on 127.0.0.1:$SitlForwardPort within $TimeoutSec s. " + ($why -join ' | '))
}
# its own port, said only: the scenarios do not use it
$api = Get-SitlApi $Port
Set-Content -Path (Join-Path $Run 'start.txt') -Encoding utf8 -Value @("version=$Version", "folder=$Run", "msp=127.0.0.1:$SitlForwardPort (forwarder)", "api=$fwdApi",
    "own=127.0.0.1:$Port $(if ($api) { 'answers' } else { 'does not answer from Windows' })", "wsl=$($p.Id)", "feed=$([bool]$Feed)", "started=$((Get-Date).ToString('s'))")
Write-Host ("Betaflight $Version (SITL) answers MSP $fwdApi through its forwarder on 127.0.0.1:$SitlForwardPort" + $(if ($api) { ", and on its own 127.0.0.1:$Port" } else { " (its own 127.0.0.1:$Port does not answer from Windows)" }) + "; its folder: $Run")
Write-Output $Run
