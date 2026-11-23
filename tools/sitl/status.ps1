<#
Whether a SITL runs, which, in which folder, how often it was started, and whether it answers MSP from Windows;
and which versions are built. Exit code 0 when one runs and answers.

  tools\sitl\status.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not (Test-SitlWsl)) { Write-Host "No WSL distribution '$SitlDistro' on this PC."; exit 2 }
$built = (& wsl.exe -d $SitlDistro -u root -- bash -c "cd /opt/draupne-sitl 2>/dev/null && ls -d */betaflight_SITL.elf 2>/dev/null | cut -d/ -f1 | sort -V | tr '\n' ' '") -join ''
Write-Host ("built: " + $(if ($built.Trim()) { $built.Trim() } else { 'none (tools\sitl\build.ps1)' }))
$code = Invoke-SitlScript @('status')
$api = Get-SitlApi $SitlMspPort
Write-Host $(if ($api) { "MSP from Windows: 127.0.0.1:$SitlMspPort answers, API $api" } else { "MSP from Windows: nothing answers on 127.0.0.1:$SitlMspPort" })
$fwdApi = Get-SitlApi $SitlForwardPort
Write-Host $(if ($fwdApi) { "MSP from Windows through the forwarder: 127.0.0.1:$SitlForwardPort answers, API $fwdApi" } else { "MSP from Windows through the forwarder: nothing answers on 127.0.0.1:$SitlForwardPort" })
if ($code -eq 0 -and $api) { exit 0 } else { exit 1 }
