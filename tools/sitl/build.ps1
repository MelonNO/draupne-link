<#
Betaflight as a program (SITL), built inside WSL from Betaflight's own source - one version, or all of them.

  tools\sitl\build.ps1 -Version 4.4.1      the real drone's version
  tools\sitl\build.ps1 -All                4.4.1, 4.5.5, 2025.12.5 and 4.3.2

Each goes to /opt/draupne-sitl/<version>/betaflight_SITL.elf in WSL, with build.txt beside it (the commit, the
compiler, the make line, the one patch 4.3 needs, the program's SHA-256). Building again builds it anew from the tag.
#>
param(
    [string[]]$Version = @('4.4.1'),
    [switch]$All
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not (Test-SitlWsl)) { throw "No WSL distribution '$SitlDistro' on this PC (docs/TESTING.md, 'Betaflight as a program')." }
if ($All) { $Version = $SitlVersions }
$failed = @()
foreach ($v in $Version) {
    Write-Host ("{0:HH:mm:ss}  Betaflight {1} SITL ..." -f (Get-Date), $v)
    $code = Invoke-SitlScript @('build', $v)
    if ($code -ne 0) { $failed += $v }
}
if ($failed) {
    Write-Host "NOT BUILT: $($failed -join ', ')"
    exit 1
}
