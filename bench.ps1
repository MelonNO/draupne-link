<#
The test bench: the whole of DRAUPNE Link (and DRAUPNE Flight) tested on this PC. See docs/TESTING.md.

  .\bench.ps1 quick                 a quarter of an hour: the bench's own parts, the main scenarios, a few bad links
  .\bench.ps1 full                  every suite once
  .\bench.ps1 full -Hours 8         every suite, then the long run for what is left of 8 hours on the clock
  .\bench.ps1 soak -Hours 2         the long run only
  .\bench.ps1 night -Until 07:30    the night: sleep and wake, one long flight, the phone app, flights - until then
  .\bench.ps1 ui -Tests *Fly*       one suite (self ui link edge emulator sitl findings), or some of its scenarios
  .\bench.ps1 ui -Screen 1093x584   the app on a screen that small (a 1366 x 768 laptop at 125 %)
  .\bench.ps1 report -Root <folder> the report of a run's folder written again
  .\bench.ps1 hw                    REAL HARDWARE: the Windows app, sealed off, against this bench's one real phone
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
    [string]$Root = ''
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
if ($Run -eq 'quick') { $env:BENCH_QUICK = 'true' } else { $env:BENCH_QUICK = 'false' }
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
        $p = Start-Process -FilePath $gradle -ArgumentList $gradleArgs -WorkingDirectory $repo -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
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
    sitl     = @('benchSitl', 'Real Betaflight firmware')
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
    # TODO (R5, the keeper / dead man's switch): `gradlew boardSafe` is not built yet.
    # Skipped while build.gradle registers no task of that name; from then on it runs here.
    if (-not (Select-String -Path (Join-Path $repo 'build.gradle') -Pattern 'register\(\s*[''"]boardSafe[''"]' -Quiet)) {
        Write-Host '          boardSafe: no such task yet (TODO R5) - skipped'
        return
    }
    Write-Host ("{0:HH:mm:ss}  The board made safe ..." -f (Get-Date))
    $t0 = Get-Date
    $code = Invoke-Gradle @('--offline', '--console=plain', 'boardSafe') (Join-Path $runRoot 'logs\boardSafe.log')
    $mins = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
    [void]$results.Add("boardSafe`t$mins min`t`t`texit $code")
    if ($code -ne 0) { Write-Host "          boardSafe ended with exit code $code - LOOK AT THE BOARD (logs\boardSafe.log)" }
}
# <<< R4 --------------------------------------------------------------------------------------------------------------

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
    if (Test-Path $log) { Get-Content $log | Where-Object { $_ -match '^(done|FAILED|battery|airplane|deep doze|ports led|STILL|Nothing done|What the bench|NOT everything)' } | ForEach-Object { Write-Host "          $_" } }
    if ($code -ne 0) { Write-Host "          phoneReset ended with exit code $code - LOOK AT THE PHONE (logs\phoneReset.log); with it back on adb: .\gradlew phoneReset" }
}
# <<< R5 --------------------------------------------------------------------------------------------------------------

if ($Run -ne 'report') {
    switch ($Run) {
        'quick' { $order = @('self', 'ui', 'link') }
        'full'  { $order = @('self', 'ui', 'link', 'edge', 'findings', 'emulator', 'sitl', 'soak') }
        default { $order = @($Run) }
    }
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
        if ($s -eq 'sitl' -and -not (Test-Wsl)) {
            [void]$pending.Add("Real Betaflight firmware - waits for the administrator step (docs/TESTING.md, 'Betaflight').   .\bench.ps1 sitl")
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
            try {
                Invoke-Suite $suites[$s][0] $suites[$s][1]
            } finally {
                if ($s -eq 'hw') { Invoke-BoardSafe }
                if ($s -eq 'phone') { Invoke-PhoneReset }   # R5: the phone run ends with the phone's battery and doze as they really are
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
