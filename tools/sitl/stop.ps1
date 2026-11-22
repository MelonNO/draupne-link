<#
The SITL that runs, ended: Betaflight and its supervisor inside WSL, then the hidden wsl.exe that kept them. Nothing
of its folder is deleted. Nothing running is no error.

  tools\sitl\stop.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not (Test-SitlWsl)) { Write-Host "No WSL distribution '$SitlDistro': no SITL runs."; return }
$folder = (& wsl.exe -d $SitlDistro -u root -- bash -c "cat /opt/draupne-sitl/current 2>/dev/null") -join ''
[void](Invoke-SitlScript @('stop'))
# the wsl.exe start.ps1 left running for it: gone by itself once the supervisor ended; else ended here
if ($folder -match '^/mnt/([a-z])/(.*)$') {
    $pidFile = Join-Path ($matches[1].ToUpper() + ':\' + ($matches[2] -replace '/', '\')) 'wsl.pid'
    if (Test-Path $pidFile) {
        $wslPid = [int](Get-Content $pidFile -TotalCount 1)
        $w = Get-Process -Id $wslPid -ErrorAction SilentlyContinue
        if ($w -and $w.ProcessName -eq 'wsl' -and -not $w.WaitForExit(5000)) { Stop-Process -Id $wslPid -Force -ErrorAction SilentlyContinue }
    }
}
if (Get-SitlApi $SitlMspPort 500) { Write-Host "Something still answers MSP on 127.0.0.1:$SitlMspPort." ; exit 1 }
