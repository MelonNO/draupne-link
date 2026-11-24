<#
  The SITL lab: a whole fleet on real ArduPilot (SITL) for trying and flying DRAUPNE Link by hand.

  .\sitl-fleet.ps1                  as this PC's settings say (the first run makes them: auto-sized)
  .\sitl-fleet.ps1 -Edit            open this PC's settings (%LOCALAPPDATA%\DraupneBench\sitl-lab.settings)
  .\sitl-fleet.ps1 -Phones 4 -Drones 6     for this run: 4 drones flown by the phone app, 6 more fleet-only
  .\sitl-fleet.ps1 -Memory 3072 -Cores 4 -Windows    each emulator bigger, the phones' screens shown
  .\sitl-fleet.ps1 -Phones 0 -Drones 8     no emulators: fleet-only drones (quick)
  .\sitl-fleet.ps1 -Lat 59.91 -Lon 10.72 [-Alt 120]  somewhere else (the ground's height looked up when -Alt is left out)
  .\sitl-fleet.ps1 -Build           build the phone app's bench build again first (after phone app changes)

  Phone drones: the real phone app (the bench build - never released) in an Android emulator, its flight controller
  the SITL drone. Connect to one in DRAUPNE Link and fly it with the radio: camera, OSD, modes, the fleet functions.
  Fleet-only drones: followers, reserves and DISPATCH, without a pilot session.
  Then start DRAUPNE Link as usual: everything shows as VIRTUAL. Ctrl+C here ends it all.
  Not together with the virtual fleet (Virtual fleet.bat): they share one file.
#>
param(
    [int]$Phones = -1,
    [int]$Drones = -1,
    [int]$Memory = 0,
    [int]$Cores = 0,
    [switch]$Windows,
    [double]$Lat = [double]::NaN,
    [double]$Lon = [double]::NaN,
    [double]$Alt = [double]::NaN,
    [switch]$Edit,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$settingsDir = Join-Path $env:LOCALAPPDATA 'DraupneBench'
$settings = Join-Path $settingsDir 'sitl-lab.settings'
if (-not (Test-Path $settings)) {
    New-Item -ItemType Directory -Force $settingsDir | Out-Null
    Copy-Item (Join-Path $PSScriptRoot 'sitl-lab.settings') $settings
    Write-Host "This PC's lab settings made: $settings"
}
if ($Edit) {
    Start-Process notepad.exe $settings
    return
}

function Stop-Lab {
    # only the lab's own: SITL instances 60 and up, emulators of its own device draupne-lab (the bench's and any other are left alone)
    Get-CimInstance Win32_Process -Filter "Name = 'ArduCopter.elf'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match ' -I(\d+)' -and [int]$Matches[1] -ge 60 } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(emulator|qemu-system)' -and $_.CommandLine -match '-avd draupne-lab\b' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

$inv = [cultureinfo]::InvariantCulture
$gradleArgs = @('sitlFleet', '-q', '--console=plain', "-Psettings=$settings")
if ($Phones -ge 0) { $gradleArgs += "-Pphones=$Phones" }
if ($Drones -ge 0) { $gradleArgs += "-PfleetOnly=$Drones" }
if ($Memory -gt 0) { $gradleArgs += "-PemulatorMemoryMb=$Memory" }
if ($Cores -gt 0) { $gradleArgs += "-PemulatorCores=$Cores" }
if ($Windows) { $gradleArgs += '-PemulatorWindows=true' }
if (-not [double]::IsNaN($Lat) -and -not [double]::IsNaN($Lon)) {
    $gradleArgs += @("-Plat=$($Lat.ToString($inv))", "-Plon=$($Lon.ToString($inv))")
    if (-not [double]::IsNaN($Alt)) { $gradleArgs += "-Palt=$($Alt.ToString($inv))" }
}

$ErrorActionPreference = 'Continue' # Java's and Gradle's notes on stderr are no failure
$bench = Get-ChildItem (Join-Path $PSScriptRoot 'build\flight-bench\*-bench.apk') -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
# the phone app's code changed since the bench build (overlay, the bench's own, the upstream app): built again
$newestSource = Get-ChildItem (Join-Path $PSScriptRoot 'flight-app\overlay'), (Join-Path $PSScriptRoot 'flight-app\bench-overlay'), (Join-Path $PSScriptRoot 'upstream') -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\(build|\.gradle|\.git)\\' } | Sort-Object LastWriteTime | Select-Object -Last 1
$stale = $bench -and $newestSource -and $newestSource.LastWriteTime -gt $bench.LastWriteTime
if ($Build -or ($Phones -ne 0 -and (-not $bench -or $stale))) {
    Write-Host ($(if ($stale) { "The phone app changed since its bench build ($($newestSource.Name)): building it again (a few minutes) ..." } else { 'Building the phone app''s bench build (a few minutes) ...' }))
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'flight-app\build.ps1') -Bench
    if ($LASTEXITCODE -ne 0) { Write-Host 'The bench build failed: see above.'; exit 1 }
}

Stop-Lab
$env:JAVA_TOOL_OPTIONS = ("-Djava.net.preferIPv4Stack=true $env:JAVA_TOOL_OPTIONS").Trim()
try {
    & "$PSScriptRoot\gradlew.bat" @gradleArgs
} finally {
    Stop-Lab
    Write-Host 'SITL lab stopped.'
}
