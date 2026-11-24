<#
  ArduPilot SITL drones for trying the fleet in DRAUPNE Link by hand.

  .\sitl-fleet.ps1                      4 drones at the virtual fleet's usual place
  .\sitl-fleet.ps1 -Drones 8            8 drones (1 - 20)
  .\sitl-fleet.ps1 -Lat 59.91 -Lon 10.72 [-Alt 120]   somewhere else (the ground's height looked up when -Alt is left out)

  Then start DRAUPNE Link as usual: the drones show as VIRTUAL on the map and in FLEET. Right-click them for SAFE TO
  AUTOLAUNCH, right-click the map for DISPATCH. Mission Planner can watch any of them on the TCP port printed for it.
  Ctrl+C ends them all. Not at the same time as the virtual fleet (Virtual fleet.bat): they share one file.
#>
param(
    [ValidateRange(1, 20)][int]$Drones = 4,
    [double]$Lat = [double]::NaN,
    [double]$Lon = [double]::NaN,
    [double]$Alt = [double]::NaN
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Stop-SitlFleet {
    # only this script's SITL drones (instances 60 and up); the tests' and anything else are left alone
    Get-CimInstance Win32_Process -Filter "Name = 'ArduCopter.elf'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match ' -I(\d+)' -and [int]$Matches[1] -ge 60 } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

$gradleArgs = @('sitlFleet', '-q', '--console=plain', "-Pdrones=$Drones")
if (-not [double]::IsNaN($Lat) -and -not [double]::IsNaN($Lon)) {
    $gradleArgs += @("-Plat=$($Lat.ToString([cultureinfo]::InvariantCulture))", "-Plon=$($Lon.ToString([cultureinfo]::InvariantCulture))")
    if (-not [double]::IsNaN($Alt)) { $gradleArgs += "-Palt=$($Alt.ToString([cultureinfo]::InvariantCulture))" }
}

Stop-SitlFleet
$env:JAVA_TOOL_OPTIONS = ("-Djava.net.preferIPv4Stack=true $env:JAVA_TOOL_OPTIONS").Trim()
$ErrorActionPreference = 'Continue' # Java's notes on stderr (JAVA_TOOL_OPTIONS) are no failure
try {
    & "$PSScriptRoot\gradlew.bat" @gradleArgs
} finally {
    Stop-SitlFleet
    Write-Host 'SITL drones stopped.'
}
