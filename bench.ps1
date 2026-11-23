<#
The test bench: the whole of DRAUPNE Link (and DRAUPNE Flight) tested on this PC. See docs/TESTING.md.

  .\bench.ps1 quick                 a quarter of an hour: the bench's own parts, the main scenarios, a few bad links
  .\bench.ps1 full                  every suite once
  .\bench.ps1 full -Hours 8         every suite, then the long run for what is left of 8 hours on the clock
  .\bench.ps1 soak -Hours 2         the long run only
  .\bench.ps1 night -Until 07:30    the night: sleep and wake, one long flight, the phone app, flights - until then
  .\bench.ps1 ui -Tests *Fly*       one suite (self ui link edge emulator sitl findings), or some of its scenarios
  .\bench.ps1 ui -Screen 1093x584   the app on a screen that small (a 1366 x 768 laptop at 125 %)
  .\bench.ps1 emulator -Avd draupne-a26   the phone app on another Android (draupne-a26 / a30 / a33 / a35: 8 / 11 / 13 / 15)
  .\bench.ps1 sitl                  Betaflight 4.4.1 itself, as a program in WSL (-Betaflight 4.5.5 / 2025.12.5 another)
  .\bench.ps1 report -Root <folder> the report of a run's folder written again
  .\bench.ps1 hw                    REAL HARDWARE: the Windows app, sealed off, against this bench's one real phone
  .\bench.ps1 hw -Tests *HwLinkMatrix* -Quick   the bad-network box on the real phone, a few cells only (-Quick: any suite's quick cut)
  .\bench.ps1 hw -Tests *HwDroneSetup*          every Drone setup row on the real board, then CONFIRM (battery out, props off)
  .\bench.ps1 hw -Tests *HwNetworkSwitch* -Quick   the phone's own network switched under the link (Wi-Fi off / on, airplane 10 s)
  .\bench.ps1 phone                 REAL HARDWARE: the real phone itself, on adb (-Tests *PhoneCrash*; -Tests *PhoneNight* -Hours 8)

The app under test runs sealed off: its own settings and folders, made-up drones, no real phone, board or internet.
The real mouse and screen are used while no real drone is armed (-Mode quiet: never; -Mode desktop: always).
Every run keeps its folder under %LOCALAPPDATA%\DraupneBench\runs - nothing is deleted by the bench.

hw and phone are the two runs that reach real hardware (docs/TESTING.md, "Real hardware"): the app is sealed off all
the same, but its fence lets it reach the one phone of %LOCALAPPDATA%\DraupneBench\hardware.properties. Props off.
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('quick', 'full', 'soak', 'night', 'self', 'ui', 'link', 'edge', 'emulator', 'sitl', 'findings', 'report', 'hw', 'phone')]
    [string]$Run = 'quick',
    [double]$Hours = 0,
    [string]$Until = '',
    [ValidateSet('auto', 'desktop', 'quiet')]
    [string]$Mode = 'auto',
    [string]$Tests = '',
    [string]$Scale = '',
    [string]$Screen = '',
    [string]$Root = '',
    # the emulator's Android device for this run (its folder under %LOCALAPPDATA%\DroidDroneBuild\avd); left out: draupne-bench
    [string]$Avd = '',
    # the Betaflight the sitl suite runs as a program (tools\sitl): 4.4.1 (the drone's), 4.5.5, 2025.12.5, 4.3.2
    [string]$Betaflight = '4.4.1',
    # a suite's scenarios with a quick cut of their own run only that (BENCH_QUICK): the bad-link tables' few cells
    [switch]$Quick
)
$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
$base = Join-Path $env:LOCALAPPDATA 'DraupneBench'
$started = Get-Date
if ($Root) { $runRoot = $Root } else {
    $runRoot = Join-Path $base ('runs\' + $started.ToString('MMdd-HHmm') + '-' + $Run)
    # two runs started in one minute shared a folder, the second's results over the first's (2026-11-21): -2, -3 ...
    $n = 2
    $first = $runRoot
    while (Test-Path $runRoot) { $runRoot = "$first-$n"; $n++ }
}
New-Item -ItemType Directory -Force (Join-Path $runRoot 'logs') | Out-Null

$env:BENCH_ROOT = $runRoot
$env:BENCH_MODE = $Mode
$env:JAVA_TOOL_OPTIONS = '-Djava.net.preferIPv4Stack=true'
# flight-app\build.ps1 leaves JAVA_HOME at its JDK 17 in the window it ran in: the PC's build needs this PC's own
# (2026-11-19: a regression started right after a phone build failed at once with "release version 21 not supported")
$javaRelease = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'release' } else { '' }
if ($javaRelease -and (Test-Path $javaRelease) -and ((Get-Content $javaRelease -Raw) -match 'JAVA_VERSION="(1[0-9]|20)\.')) {
    $own = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    if (-not $own) { $own = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine') }
    if ($own) { $env:JAVA_HOME = $own } else { throw "JAVA_HOME is a JDK older than 21 ($env:JAVA_HOME): start the bench in a new window." }
}
if ($Run -eq 'quick' -or $Quick) { $env:BENCH_QUICK = 'true' } else { $env:BENCH_QUICK = 'false' }
if ($Scale) { $env:BENCH_SCALE = $Scale }   # Windows' text scaling for the app under test: 1, 1.25, 1.5, 2
if ($Screen) { $env:BENCH_SIZE = $Screen }   # the app on a screen this small, e.g. 1093x584 (a 1366 x 768 laptop at 125 %): every scenario
# everything started from here inherits it: a real drone's link on this PC goes first
# (R4, the hardware rig: not in the two hardware runs - delay figures are measured there, and theirs is the real link)
$hardwareRun = $Run -in @('hw', 'phone')
if (-not $hardwareRun) { (Get-Process -Id $PID).PriorityClass = 'BelowNormal' }
if ($Hours -gt 0) { $deadline = $started.AddHours($Hours) } else { $deadline = $null }

$gradle = Join-Path $repo 'gradlew.bat'
$mapBuild = 'map-build'   # the night's map part builds here, under the run's folder
$results = New-Object System.Collections.ArrayList
$pending = New-Object System.Collections.ArrayList

function Invoke-Suite([string]$task, [string]$what, [string]$logName = '', [int]$killAfterMin = 0) {
    if (-not $logName) { $logName = $task }
    if ($deadline -and (Get-Date) -ge $deadline) {
        [void]$pending.Add("$what - the run's time was up.   .\bench.ps1 $($task.Substring(5).ToLower())")
        return
    }
    $log = Join-Path $runRoot "logs\$logName.log"
    $gradleArgs = @('--offline', '--console=plain', $task)
    if ($Tests) { $gradleArgs += @('--tests', $Tests) }
    Write-Host ("{0:HH:mm:ss}  {1} ..." -f (Get-Date), $what)
    $t0 = Get-Date
    $code = Invoke-Gradle $gradleArgs $log $killAfterMin
    $mins = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
    $passed = (Select-String -Path $log -Pattern ' PASSED$' -ErrorAction SilentlyContinue | Measure-Object).Count
    $failed = (Select-String -Path $log -Pattern ' FAILED$' -ErrorAction SilentlyContinue | Measure-Object).Count
    [void]$results.Add("$logName`t$mins min`t$passed passed`t$failed failed`texit $code")
    Write-Host ("          {0} passed, {1} failed, {2} min" -f $passed, $failed, $mins)
}

# gradlew with its output in a file. Not "& gradlew *> file": Windows PowerShell turns a program's every stderr line
# into an error of its own, and Java prints one at each start.
function Invoke-Gradle([string[]]$gradleArgs, [string]$log, [int]$killAfterMin = 0) {
    if ($killAfterMin -le 0) {
        # WaitForExit, not Start-Process -Wait: -Wait waits for every process gradlew started too, and a Gradle daemon
        # gradlew starts lives on for hours after the build - the run then never came back (seen 2026-11-22, twice).
        $p = Start-Process -FilePath $gradle -ArgumentList $gradleArgs -WorkingDirectory $repo -NoNewWindow -PassThru `
            -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
        $h = $p.Handle   # kept, or the exit code is lost once it has ended
        $p.WaitForExit()
        return $p.ExitCode
    }
    # A part of the night gets its time and no more: a scenario that hangs must not take the rest of the night with it.
    # Past the limit the bench's own test programs are ended - found by this run's folder on their command line, never
    # anything else - and Gradle then ends by itself.
    $p = Start-Process -FilePath $gradle -ArgumentList $gradleArgs -WorkingDirectory $repo -NoNewWindow -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
    $h = $p.Handle   # kept, or the exit code is lost once it has ended
    if (-not $p.WaitForExit($killAfterMin * 60000)) {
        Write-Host ("          not through after {0} min: ended by the bench" -f $killAfterMin)
        $mark = 'bench.root=' + $runRoot   # (not the map part that runs beside the night's others: its build folder says which it is)
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($mark) -and -not $_.CommandLine.Contains($mapBuild) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        if (-not $p.WaitForExit(120000)) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
        return -9
    }
    return $p.ExitCode
}

# The night (docs/TESTING.md): long scenarios, each with its share of the time until -Until. One flight for hours
# first (begun before midnight it flies across it), then sleep and wake, then the real phone app in the emulator,
# then flight after flight until the time is up. A part that overran leaves the later ones less; one that ended
# early leaves them more. The map on an internet that comes and goes runs beside them all night: it has no window,
# takes next to no processor (it waits for a server on this PC most of the time), and so costs the others nothing -
# with a build folder of its own, so the parts' own compiles never meet it.
function Invoke-Night {
    if ($Tests) {   # one of them tried out for a few minutes: .\bench.ps1 night -Tests *LongFlight* -Hours 0.2
        $h = $(if ($Hours -gt 0) { $Hours } else { 0.15 })
        $script:deadline = $null
        $env:BENCH_HOURS = [string]$h
        $env:BENCH_TIMEOUT = ([string][math]::Ceiling($h * 60 + 25)) + ' m'
        Invoke-Suite 'benchNight' ("A night scenario tried out ({0} h)" -f $h) 'night-try' ([int][math]::Ceiling($h * 60 + 40))
        return
    }
    $end = [datetime]::ParseExact($Until, 'HH:mm', [cultureinfo]::InvariantCulture)
    if ($end -le (Get-Date)) { $end = $end.AddDays(1) }

    # beside the others, for the whole night
    $mapHours = [math]::Round(($end - (Get-Date)).TotalHours - 0.15, 2)
    $mapLog = Join-Path $runRoot 'logs\night-0-map.log'
    $map = $null
    if ($mapHours -ge 0.2) {
        $env:BENCH_HOURS = [string]$mapHours
        $env:BENCH_TIMEOUT = ([string][math]::Ceiling($mapHours * 60 + 25)) + ' m'
        $mapArgs = @('--offline', '--console=plain', 'benchNight', '--tests', '*MapNight*',
            ('-PaltBuild=' + (Join-Path $runRoot $mapBuild).Replace('\', '/')),
            '--project-cache-dir', (Join-Path $runRoot 'map-gradle').Replace('\', '/'))
        Write-Host ("{0:HH:mm:ss}  The map on an internet that comes and goes ({1} h), beside the others ..." -f (Get-Date), $mapHours)
        $map = Start-Process -FilePath $gradle -ArgumentList $mapArgs -WorkingDirectory $repo -NoNewWindow -PassThru `
            -RedirectStandardOutput $mapLog -RedirectStandardError ($mapLog + '.err')
        $mapHandle = $map.Handle
        $mapStarted = Get-Date
        # its compile is the one thing of it that takes processor: over before the first part begins
        $compiled = (Get-Date).AddMinutes(6)
        while ((Get-Date) -lt $compiled -and -not $map.HasExited -and
            -not (Select-String -Path $mapLog -Pattern 'Task :benchNight' -Quiet -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 3 }
        if ($map.HasExited) { Write-Host '          the map part ended at its start: see logs\night-0-map.log.err' }
    }

    $parts = @(
        @('benchNight', '*LongFlight*', 0.30, 'night-1-long-flight', 'One flight that lasts for hours'),
        @('benchNight', '*SleepCycle*', 0.20, 'night-2-sleep', 'Sleep and wake, for hours'),
        @('benchNight', '*PhoneNight*', 0.22, 'night-3-phone', 'The real phone app left running'),
        @('benchSoak', '', 0.28, 'night-4-flights', 'Flight after flight')
    )
    $sharesLeft = 1.0
    foreach ($part in $parts) {
        $left = ($end - (Get-Date)).TotalHours
        $hours = [math]::Round($left * $part[2] / $sharesLeft, 2)
        $sharesLeft -= $part[2]
        if ($part[1] -eq '*PhoneNight*' -and -not (Test-Emulator)) {
            [void]$pending.Add("$($part[4]) - no emulator set up yet (docs/TESTING.md, 'The emulator').")
            continue
        }
        if ($part[1] -eq '*PhoneNight*' -and -not (Test-BenchBuild)) {
            [void]$pending.Add("$($part[4]) - $noBenchBuild.")
            continue
        }
        if ($hours -lt 0.2) {
            [void]$pending.Add("$($part[4]) - the night's time was up.")
            continue
        }
        $env:BENCH_HOURS = [string]$hours
        $env:BENCH_TIMEOUT = ([string][math]::Ceiling($hours * 60 + 25)) + ' m'
        $script:Tests = $part[1]
        Invoke-Suite $part[0] ("{0} ({1} h)" -f $part[4], $hours) $part[3] ([int][math]::Ceiling($hours * 60 + 40))
    }
    $script:Tests = ''

    if ($map) {
        $wait = [math]::Max(60000, [int](($end - (Get-Date)).TotalMilliseconds) + 20 * 60000)
        if (-not $map.WaitForExit($wait)) {
            Write-Host '          the map part not through at the night''s end: ended by the bench'
            Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($mapBuild) -and $_.CommandLine.Contains($runRoot) } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            if (-not $map.WaitForExit(120000)) { Stop-Process -Id $map.Id -Force -ErrorAction SilentlyContinue }
        }
        $mins = [math]::Round(((Get-Date) - $mapStarted).TotalMinutes, 1)
        $passed = (Select-String -Path $mapLog -Pattern ' PASSED$' -ErrorAction SilentlyContinue | Measure-Object).Count
        $failed = (Select-String -Path $mapLog -Pattern ' FAILED$' -ErrorAction SilentlyContinue | Measure-Object).Count
        [void]$results.Add("night-0-map`t$mins min`t$passed passed`t$failed failed`texit $($map.ExitCode)")
        Write-Host ("          the map: {0} passed, {1} failed, {2} min" -f $passed, $failed, $mins)
    }
}

function Test-Emulator {
    Test-Path (Join-Path $env:LOCALAPPDATA 'DroidDroneBuild\avd\draupne-bench.avd')
}

# Q-006 (2.3.0-pc.58): the emulator flies the bench build - the released app answers over Tailscale only, and the
# emulator has none. Without one built, the emulator's scenarios are put off with the way to make it.
function Test-BenchBuild {
    [bool](Get-ChildItem (Join-Path $repo 'build\flight-bench') -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1)
}
$noBenchBuild = 'no bench build of the phone app yet (the emulator flies it since 2.3.0-pc.58: flight-app\build.ps1 -Bench)'

# The emulator the scenarios use is whatever runs as emulator-5584 (bench\PhoneRig starts draupne-bench there when
# none does). -Avd puts another Android device there for this run - the phone app on Android 8, 11, 13, 15 - and a run
# without it gets draupne-bench back: one left there by a run with -Avd is ended first, so a night never flies the
# phone app on another Android by mistake. Only the bench's own devices run on that port; nothing else is ended.
$emulatorSerial = 'emulator-5584'
function Set-BenchEmulator {
    $want = $(if ($Avd) { $Avd } else { 'draupne-bench' })
    $avdHome = Join-Path $env:LOCALAPPDATA 'DroidDroneBuild\avd'
    if (-not (Test-Path (Join-Path $avdHome "$want.avd"))) { throw "No Android device $want in $avdHome" }
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { return }
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # adb's first line, as text ("" when it says nothing - with no emulator there it writes to stderr alone)
    function Get-AdbWord([string[]]$words) { $line = & $adb -s $emulatorSerial @words 2>$null | Select-Object -First 1; return "$line".Trim() }
    try {
        $now = Get-AdbWord @('emu', 'avd', 'name')
        if ($now -and $now -ne $want) {
            Write-Host ("{0:HH:mm:ss}  The emulator runs {1}, this run wants {2}: {1} is ended ..." -f (Get-Date), $now, $want)
            & $adb -s $emulatorSerial emu kill 2>$null | Out-Null
            $gone = (Get-Date).AddSeconds(90)
            while ((Get-Date) -lt $gone -and (Get-AdbWord @('get-state')) -eq 'device') { Start-Sleep -Seconds 2 }
            Start-Sleep -Seconds 3
            $now = ''
        }
        if (-not $now -and $Avd) {
            Write-Host ("{0:HH:mm:ss}  The emulator: {1} started ..." -f (Get-Date), $want)
            $env:ANDROID_AVD_HOME = $avdHome
            Start-Process -FilePath (Join-Path $sdk 'emulator\emulator.exe') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runRoot 'emulator.log') `
                -RedirectStandardError (Join-Path $runRoot 'emulator.log.err') -ArgumentList @('-avd', $want, '-no-window', '-no-audio', '-no-boot-anim',
                '-gpu', 'swiftshader_indirect', '-camera-back', 'emulated', '-camera-front', 'emulated', '-no-snapshot', '-port', '5584') | Out-Null
            $up = (Get-Date).AddMinutes(6)
            while ((Get-Date) -lt $up -and (Get-AdbWord @('shell', 'getprop', 'sys.boot_completed')) -ne '1') { Start-Sleep -Seconds 3 }
            if ((Get-AdbWord @('shell', 'getprop', 'sys.boot_completed')) -ne '1') { throw "The emulator $want did not start within 6 minutes (emulator.log)" }
            Start-Sleep -Seconds 4
        }
        if ($Avd) {
            $android = Get-AdbWord @('shell', 'getprop', 'ro.build.version.release')
            [void]$results.Add("emulator`t$want`tAndroid $android")
            Write-Host "          the emulator: $want, Android $android"
        }
    } finally {
        $ErrorActionPreference = $saved
    }
}

function Test-Wsl {
    try {
        $names = & wsl.exe -l -q 2>$null
        return ($LASTEXITCODE -eq 0 -and $names)
    } catch {
        return $false
    }
}

$suites = @{
    self     = @('benchSelf', "The bench's own parts")
    ui       = @('benchUi', "The Windows app: buttons pressed, pictures taken")
    link     = @('benchLink', 'The bad-link table')
    edge     = @('benchEdge', 'Edge cases')
    findings = @('benchFindings', 'The tests of bugs found and not fixed')
    emulator = @('benchEmulator', 'The real phone app in the emulator')
    sitl     = @('benchSitl', 'Betaflight as a program (SITL)')
    soak     = @('benchSoak', 'The long run')
}

# >>> R4, the hardware rig (docs/TESTING.md, "Real hardware") ---------------------------------------------------------
$suites['hw'] = @('benchHw', 'REAL HARDWARE: the Windows app, sealed off, against the one real phone')
$suites['phone'] = @('benchHwPhone', 'REAL HARDWARE: the real phone itself')

# This PC's own hardware file: outside the repo, never committed (docs\hardware.example.properties is the one to copy).
function Test-Hardware {
    Test-Path (Join-Path $base 'hardware.properties')
}

# The last step of the hw run, whatever way it went (also after an error or Ctrl+C): the board made safe.
function Invoke-BoardSafe {
    # `gradlew boardSafe` (bench\hw\BoardSafe): STOP to every motor, read back, and whether the board is disarmed.
    # It fails when no flight controller is on USB too: a hw run without one says so here, which is right.
    Write-Host ("{0:HH:mm:ss}  The board made safe ..." -f (Get-Date))
    $t0 = Get-Date
    $safeArgs = @('--offline', '--console=plain', 'boardSafe')
    # >>> R3: boardSafe now sets the keeper's arming lock too, which stays on a board until it is restarted - so it is
    # told this bench's own board (board.port) and touches no other flight controller that may be on this PC's USB
    $boardPort = Select-String -Path (Join-Path $base 'hardware.properties') -Pattern '^\s*board\.port\s*=\s*(\S+)' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($boardPort) { $safeArgs += ('-Pport=' + $boardPort.Matches[0].Groups[1].Value) }
    # <<< R3
    $code = Invoke-Gradle $safeArgs (Join-Path $runRoot 'logs\boardSafe.log')
    $mins = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
    [void]$results.Add("boardSafe`t$mins min`t`t`texit $code")
    if ($code -ne 0) { Write-Host "          boardSafe ended with exit code $code - LOOK AT THE BOARD (logs\boardSafe.log)" }
}
# <<< R4 --------------------------------------------------------------------------------------------------------------

# >>> R3, the flight controller keeper (bench\hw\FcKeeper; docs/TESTING.md, "Real hardware", "The keeper") --------------
# The keeper is the one program that has the board's port during a hw run, and where the run's safety is kept: started
# before the suite, told "make safe and end" after it - however the run went, also after an error or Ctrl+C - and only
# then gradlew boardSafe, which finds the port free and sets the same lock once more. A hardware file that names no
# keeper (keeper.port) is a bench with no board on this PC: nothing is started, and nothing of its runs is armed.
function Test-Keeper {
    $file = Join-Path $base 'hardware.properties'
    return ((Test-Path $file) -and [bool](Select-String -Path $file -Pattern '^\s*keeper\.port\s*=\s*\d' -Quiet))
}

# Where this run's keeper keeps its files; also what tells its java from any other on this PC.
$keeperFolder = (Join-Path $runRoot 'keeper').Replace('\', '/')

function Start-Keeper {
    # gradlew fcKeeper in a Gradle of its own beside the suite's (nothing is compiled twice: it is up before the suite starts)
    $log = Join-Path $runRoot 'logs\fcKeeper.log'
    Write-Host ("{0:HH:mm:ss}  The flight controller keeper ..." -f (Get-Date))
    $p = Start-Process -FilePath $gradle -ArgumentList @('--offline', '--console=plain', 'fcKeeper', ('-Pfolder=' + $keeperFolder)) -WorkingDirectory $repo `
        -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
    $h = $p.Handle   # kept, or the exit code is lost once it has ended
    $until = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $until -and -not $p.HasExited -and
        -not (Select-String -Path $log -Pattern 'THE KEEPER IS UP' -Quiet -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 500 }
    if ($p.HasExited) {
        Write-Host "          the keeper did not start (exit $($p.ExitCode)) - logs\fcKeeper.log says why; the scenarios that need it say so and fail"
        # (R3, second round) its own word for it, when it has one: a hardware file that lets the board's failsafe run on one word alone
        Get-Content $log -ErrorAction SilentlyContinue | Where-Object { $_ -match 'THE KEEPER DID NOT START' } | Select-Object -First 1 | ForEach-Object { Write-Host "          $_" }
    } elseif (-not (Select-String -Path $log -Pattern 'THE KEEPER IS UP' -Quiet -ErrorAction SilentlyContinue)) {
        Write-Host '          the keeper is not up after 4 min (logs\fcKeeper.log); the scenarios that need it say so and fail'
    } else {
        # (R3, second round) a real board's own settings are read in its CLI first - Betaflight restarts - and only then is the
        # board taken: its first lines (its failsafe, its output limit, whether an arm window can open at all) are waited for
        # (a board that was never there says "No board" before any settings are read: that is not waited out)
        $taken = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $taken -and -not $p.HasExited -and
            -not (Select-String -Path $log -Pattern 'The board is taken' -Quiet -ErrorAction SilentlyContinue) -and
            ((Select-String -Path $log -Pattern "The board's own settings" -Quiet -ErrorAction SilentlyContinue) -or
            -not (Select-String -Path $log -Pattern 'No board' -Quiet -ErrorAction SilentlyContinue))) { Start-Sleep -Milliseconds 500 }
        $said = @(Get-Content $log -ErrorAction SilentlyContinue)
        foreach ($what in @('THE KEEPER IS UP', "The board's own settings", 'The board is taken')) {
            $said | Where-Object { $_ -match $what } | Select-Object -Last 1 | ForEach-Object { Write-Host "          $_" }
        }
        if (-not ($said | Where-Object { $_ -match 'The board is taken' })) {
            $said | Where-Object { $_ -match 'No board' } | Select-Object -Last 1 | ForEach-Object { Write-Host "          $_" }
        }
    }
    return $p
}

function Stop-Keeper($keeper) {
    # "make safe and end": lock, STOP to every motor, the motors read back, the port let go. Its exit code is its word on the board.
    Write-Host ("{0:HH:mm:ss}  The keeper: make safe and end ..." -f (Get-Date))
    $log = Join-Path $runRoot 'logs\fcKeeperEnd.log'
    $t0 = Get-Date
    $code = Invoke-Gradle @('--offline', '--console=plain', '-q', 'fcKeeperEnd') $log
    $mins = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
    [void]$results.Add("fcKeeperEnd`t$mins min`t`t`texit $code")
    if (Test-Path $log) { Get-Content $log | Where-Object { $_ -match '^(OK |NO )' } | ForEach-Object { Write-Host "          $_" } }
    if ($keeper -and -not $keeper.HasExited -and -not $keeper.WaitForExit(90000)) {
        # it did not go by itself: its java is ended - found by this run's keeper folder on its command line, never anything else
        Write-Host '          the keeper did not end by itself: ended by the bench'
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('folder=' + $keeperFolder) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        if (-not $keeper.WaitForExit(60000)) { Stop-Process -Id $keeper.Id -Force -ErrorAction SilentlyContinue }
    }
    if ($code -ne 0) { Write-Host "          the keeper did NOT say the board was left safe (exit $code, logs\fcKeeperEnd.log) - boardSafe follows: LOOK AT THE BOARD" }
    $stop = Join-Path $runRoot 'hardware-stop.txt'
    if (Test-Path $stop) { Write-Host ("          THE HARDWARE GUARD STOPPED THE SUITE: " + (Get-Content $stop -TotalCount 1)) }
}
# <<< R3 --------------------------------------------------------------------------------------------------------------

# >>> R5, the real phone's rig (bench\PhoneRig; docs/TESTING.md, "Real hardware") --------------------------------------
# The last step of the phone run, whatever way it went (also after an error, a scenario ended at its time, or Ctrl+C):
# what the bench pretends about the phone's battery to make it doze on its cable is taken back - a scenario that was
# killed never reached its own putting back. gradlew phoneReset takes the phone of the hardware file (phone.adb, or
# BENCH_PHONE_ADB), sends two orders that do nothing on a phone that needs none, and says how it found the phone.
function Invoke-PhoneReset {
    Write-Host ("{0:HH:mm:ss}  The phone: what the bench pretended taken back ..." -f (Get-Date))
    $t0 = Get-Date
    $log = Join-Path $runRoot 'logs\phoneReset.log'
    $code = Invoke-Gradle @('--offline', '--console=plain', '-q', 'phoneReset') $log
    $mins = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
    [void]$results.Add("phoneReset`t$mins min`t`t`texit $code")
    if (Test-Path $log) { Get-Content $log | Where-Object { $_ -match '^(done|FAILED|battery|airplane|network|deep doze|stay awake|ports led|STILL|Nothing done|What the bench|NOT everything)' } | ForEach-Object { Write-Host "          $_" } }
    if ($code -ne 0) { Write-Host "          phoneReset ended with exit code $code - LOOK AT THE PHONE (logs\phoneReset.log); with it back on adb: .\gradlew phoneReset" }
}
# <<< R5 --------------------------------------------------------------------------------------------------------------

# >>> R7, Betaflight as a program (tools\sitl; docs/TESTING.md, "Betaflight as a program") -----------------------------
# The sitl suite gets Betaflight -Betaflight (4.4.1) started in WSL before it - built first if it is not, a fresh board
# in the run's folder (sitl\<version>: its eeprom.bin and sitl.log stay there), the level-attitude feeder beside it -
# and stopped after it, however it went. One SITL at a time: one that runs already (started by hand) is not touched,
# and the suite waits for another run.
function Start-Sitl {
    $tools = Join-Path $repo 'tools\sitl'
    Write-Host ("{0:HH:mm:ss}  Betaflight {1} as a program ..." -f (Get-Date), $Betaflight)
    $log = Join-Path $runRoot 'logs\sitl-start.log'
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $built = (& wsl.exe -d Ubuntu -u root -- bash -c "test -x /opt/draupne-sitl/$Betaflight/betaflight_SITL.elf && echo yes") -join ''
        if ($built -ne 'yes') {
            Write-Host "          not built yet: tools\sitl\build.ps1 -Version $Betaflight (a few minutes the first time)"
            & (Join-Path $tools 'build.ps1') -Version $Betaflight *>> $log
        }
        # its words (Write-Host) into the log, its one output - the folder - kept
        $said = @(& (Join-Path $tools 'start.ps1') -Version $Betaflight -Run (Join-Path $runRoot "sitl\$Betaflight") -Feed *>&1)
        $said | ForEach-Object { "$_" } | Add-Content -Path $log
        $folder = $said | Where-Object { $_ -is [string] } | Select-Object -Last 1
    } catch {
        $folder = $null
        Add-Content -Path $log -Value $_.Exception.Message
    } finally {
        $ErrorActionPreference = $saved
    }
    if (-not $folder) {
        $why = (Get-Content $log -Tail 1 -ErrorAction SilentlyContinue)
        Write-Host "          not started: $why"
        [void]$pending.Add("Betaflight as a program - SITL $Betaflight did not start ($why; logs\sitl-start.log).   .\bench.ps1 sitl")
        return $null
    }
    Write-Host "          answers MSP through its forwarder on 127.0.0.1:15761; its folder: $folder"
    $env:BENCH_SITL_VERSION = $Betaflight
    $env:BENCH_SITL_FOLDER = $folder
    # the scenarios reach SITL through its forwarder in WSL (tools\sitl\forwarder.py: UART1 on 15761, UART2 on 15762,
    # UART4 on 15764), which stays while Betaflight restarts - not on its own 5761 that WSL's forwarding now and then lost
    $env:BENCH_SITL_PORT = '15761'
    return $folder
}

function Stop-Sitl {
    Write-Host ("{0:HH:mm:ss}  Betaflight as a program: stopped" -f (Get-Date))
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & (Join-Path $repo 'tools\sitl\stop.ps1') *>> (Join-Path $runRoot 'logs\sitl-stop.log')
    } finally {
        $ErrorActionPreference = $saved
    }
}
# <<< R7 --------------------------------------------------------------------------------------------------------------

if ($Run -ne 'report') {
    switch ($Run) {
        'quick' { $order = @('self', 'ui', 'link') }
        'full'  { $order = @('self', 'ui', 'link', 'edge', 'findings', 'emulator', 'sitl', 'soak') }
        default { $order = @($Run) }
    }
    if (($order | Where-Object { $_ -in @('emulator', 'night') }) -or $Avd) { Set-BenchEmulator }
    foreach ($s in $order) {
        if ($s -eq 'night') {
            if (-not $Until -and -not $Tests) { throw "The night needs its end: .\bench.ps1 night -Until 07:30" }
            Invoke-Night
            continue
        }
        if ($s -eq 'emulator' -and -not (Test-Emulator)) {
            [void]$pending.Add("The real phone app in the emulator - no emulator set up yet (docs/TESTING.md, 'The emulator').   .\bench.ps1 emulator")
            continue
        }
        if ($s -eq 'emulator' -and -not (Test-BenchBuild)) {
            [void]$pending.Add("The real phone app in the emulator - $noBenchBuild.   .\bench.ps1 emulator")
            continue
        }
        if ($s -eq 'sitl') {
            if (-not (Test-Wsl)) {
                [void]$pending.Add("Betaflight as a program - no WSL on this PC (docs/TESTING.md, 'Betaflight as a program').   .\bench.ps1 sitl")
                continue
            }
            # R7: SITL started before the suite, stopped after it however it went
            if (-not (Start-Sitl)) { continue }
            try {
                Invoke-Suite $suites[$s][0] ("{0} {1}" -f $suites[$s][1], $Betaflight)
            } finally {
                Stop-Sitl
            }
            continue
        }
        if ($s -eq 'soak') {
            if ($deadline) {
                $left = ($deadline - (Get-Date)).TotalHours - 0.05
                if ($left -lt 0.1) {
                    [void]$pending.Add("The long run - the run's time was up.   .\bench.ps1 soak -Hours 2")
                    continue
                }
                $env:BENCH_HOURS = [string][math]::Round($left, 2)
            } elseif ($Hours -gt 0) {
                $env:BENCH_HOURS = [string]$Hours
            } elseif ($Run -eq 'full') {
                $env:BENCH_HOURS = '0.17'   # ten minutes when no time was given
            } else {
                $env:BENCH_HOURS = '0.5'
            }
            if ($Run -eq 'soak' -and $Hours -gt 0) { $deadline = $null; $env:BENCH_HOURS = [string]$Hours }
            $env:BENCH_TIMEOUT = ([string][math]::Ceiling([double]$env:BENCH_HOURS * 60 + 30)) + ' m'
        }
        # >>> R4, the hardware rig: only with this PC's hardware file; hw ends with the board made safe, however it went
        if ($s -in @('hw', 'phone')) {
            if (-not (Test-Hardware)) {
                [void]$pending.Add("Real hardware - no hardware.properties in $base yet (copy docs\hardware.example.properties there, with this bench's own phone and board).   .\bench.ps1 $s")
                continue
            }
            # >>> R5: the phone's long scenario for hours - .\bench.ps1 phone -Tests *PhoneNight* -Hours 8 (without -Hours: its few minutes)
            if ($s -eq 'phone' -and $Hours -gt 0) {
                $deadline = $null
                $env:BENCH_HOURS = [string]$Hours
                $env:BENCH_TIMEOUT = ([string][math]::Ceiling($Hours * 60 + 40)) + ' m'
            }
            # <<< R5
            $keeper = $null
            try {
                if ($s -eq 'hw' -and (Test-Keeper)) { $keeper = Start-Keeper }   # R3: the keeper has the board before the suite starts
                Invoke-Suite $suites[$s][0] $suites[$s][1]
            } finally {
                if ($s -eq 'hw' -and (Test-Keeper)) { Stop-Keeper $keeper }      # R3: ended first - boardSafe then finds the port free
                if ($s -eq 'hw') { Invoke-BoardSafe }
                if ($s -eq 'phone') { Invoke-PhoneReset }   # R5: the phone run ends with the phone's battery and doze as they really are
                # a hw scenario that switched the phone's own network (HwNetworkSwitchScenario leaves this file before its first
                # switch): the phone's network put back as the note on the phone says, also after a scenario that was killed
                if ($s -eq 'hw' -and (Test-Path (Join-Path $runRoot 'phone-network-switched.txt'))) { Invoke-PhoneReset }
            }
            continue
        }
        # <<< R4
        Invoke-Suite $suites[$s][0] $suites[$s][1]
    }
    $commit = (& git -C $repo rev-parse --short HEAD 2>$null)
    $dirty = (& git -C $repo status --porcelain 2>$null | Measure-Object).Count
    $info = @("run: $Run   mode: $Mode   started: $($started.ToString('yyyy-MM-dd HH:mm'))   ended: $((Get-Date).ToString('HH:mm'))",
        "code: beta $commit + $dirty uncommitted file(s)") + $results
    Set-Content -Path (Join-Path $runRoot 'run.txt') -Value $info -Encoding utf8
    if ($pending.Count -gt 0) { Set-Content -Path (Join-Path $runRoot 'pending.txt') -Value $pending -Encoding utf8 }
}

[void](Invoke-Gradle @('--offline', '--console=plain', '-q', 'benchReport') (Join-Path $runRoot 'logs\benchReport.log'))
$summary = Join-Path $runRoot 'summary.txt'
if (Test-Path $summary) {
    $line = (Get-Content $summary -TotalCount 1)
    Add-Content -Path (Join-Path $base 'runs.log') -Value ((Get-Date).ToString('yyyy-MM-dd HH:mm') + "`t$Run`t" + $line) -Encoding utf8
    Write-Host ''
    Write-Host $line
}
if ($pending.Count -gt 0) {
    Write-Host ''
    Write-Host 'Did not run - saved to start later:'
    $pending | ForEach-Object { Write-Host "  $_" }
}
Write-Host ''
Write-Host ("Report: " + (Join-Path $runRoot 'report\index.html'))
