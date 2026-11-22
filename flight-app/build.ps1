<#
  Builds DRAUPNE Flight for DRAUPNE Link: the pinned upstream source (../upstream) with the files in
  overlay/ laid over it. Output: build/flight/DRAUPNE-Flight-<version>.apk

  Needs: Android SDK with platforms;android-34, build-tools;34.0.0 and ndk;26.3.11579264 (ANDROID_HOME, or the
  default %LOCALAPPDATA%\Android\Sdk), and a JDK 17 (upstream's Android Gradle plugin 8.1 doesn't run on 21):
  JAVA17_HOME, or the default %LOCALAPPDATA%\DroidDroneBuild\jdk-17*.

  Signing: a release keystore outside this repo. DD_FLIGHT_KEYSTORE / DD_FLIGHT_KEYSTORE_PASSWORD, or the default
  %APPDATA%\DroidDronePC-build\flight-release.jks with its password in flight-release.password next to it (created on
  the first build). Never commit either.

  Every APK built here is scanned (scan-apk.ps1) before it reaches build/flight: one that has anything of the test
  rig's build in it is an error.

  -Bench builds the test rig's app instead (bench-overlay/README.md; NEVER RELEASED): bench-overlay/ laid over a
  source copy of its own (build/flight-bench-src), the build type "bench", signed with the rig's own key
  (DD_BENCH_KEYSTORE / DD_BENCH_KEYSTORE_PASSWORD, or the default flight-bench.jks and flight-bench.password in the
  same folder, created on the first bench build), never the release key. Output:
  build/flight-bench/DRAUPNE-Flight-<version>-bench.apk

  -UpdateTest builds one of the bench's update test's two builds instead (bench-overlay/README.md, "The update
  test's builds"; NEVER RELEASED): the released app - its package, its version, the release key, so that Android
  installs the one over the other and the normal build over both - with bench-overlay/update-test/ laid over a source
  copy of its own (build/flight-update-test-src): plain http to the bench's own web server on this PC (10.0.2.2 from
  the emulator, 127.0.0.1 from a real phone through adb reverse), and a remote update taken from there too
  (UpdateTestSource, which UpdateSource asks first in that copy alone). flight-app/overlay is not changed. Its scan
  wants the update test's marker; a normal build's and the release workflow's refuse it. Output:
  build/flight-update-test/DRAUPNE-Flight-<version>-update-test.apk
#>
param([switch]$Bench, [switch]$UpdateTest)
$ErrorActionPreference = 'Stop'
if ($Bench -and $UpdateTest) { throw '-Bench and -UpdateTest are two different builds: one at a time' }

# Windows PowerShell 5.1 turns anything a program writes to stderr into an error: run tools without that, by exit code
function Invoke-Tool([scriptblock]$command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $saved }
}
$root = Split-Path -Parent $PSScriptRoot
$upstream = Join-Path $root 'upstream'
$src = Join-Path $root $(if ($Bench) { 'build\flight-bench-src' } elseif ($UpdateTest) { 'build\flight-update-test-src' } else { 'build\flight-src' })
$out = Join-Path $root $(if ($Bench) { 'build\flight-bench' } elseif ($UpdateTest) { 'build\flight-update-test' } else { 'build\flight' })

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$jdk = if ($env:JAVA17_HOME) { $env:JAVA17_HOME } else {
    (Get-ChildItem (Join-Path $env:LOCALAPPDATA 'DroidDroneBuild') -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName
}
if (-not (Test-Path (Join-Path $sdk 'ndk\26.3.11579264'))) { throw "Android SDK with ndk;26.3.11579264 not found at $sdk" }
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\java.exe'))) { throw 'JDK 17 not found (set JAVA17_HOME)' }
if (-not (Test-Path (Join-Path $upstream 'libuvccamera\build.gradle'))) {
    Invoke-Tool { git -C $upstream submodule update --init libuvccamera }
    if ($LASTEXITCODE -ne 0) { throw 'Could not fetch upstream/libuvccamera (git submodule update failed)' }
}

# signing key: created once, kept outside the repo
$keystore = $env:DD_FLIGHT_KEYSTORE
$password = $env:DD_FLIGHT_KEYSTORE_PASSWORD
if ($Bench) {
    # the rig's own key, made and kept as the release key is; the release key is not touched or read here
    $keystore = $env:DD_BENCH_KEYSTORE
    $password = $env:DD_BENCH_KEYSTORE_PASSWORD
    if (-not $keystore) {
        $keyDir = Join-Path $env:APPDATA 'DroidDronePC-build'
        $keystore = Join-Path $keyDir 'flight-bench.jks'
        $passwordFile = Join-Path $keyDir 'flight-bench.password'
        if (-not (Test-Path $keystore)) {
            New-Item -ItemType Directory -Force $keyDir | Out-Null
            $bytes = New-Object byte[] 24
            [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
            $password = [Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x'
            Set-Content -Path $passwordFile -Value $password -NoNewline -Encoding ascii
            $env:DD_NEW_KEY_PASSWORD = $password
            Invoke-Tool { & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $keystore -storepass:env DD_NEW_KEY_PASSWORD `
                -keypass:env DD_NEW_KEY_PASSWORD -alias flight-bench -keyalg RSA -keysize 4096 -validity 10000 `
                -dname 'CN=DRAUPNE Flight BENCH (test rig only - never released)' } | Out-Null
            Remove-Item Env:DD_NEW_KEY_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
            Write-Host "New key for the test rig's build: $keystore"
        }
        $password = (Get-Content $passwordFile -Raw).Trim()
    }
    # its certificate's SHA-256: the built APK must be signed by this key and no other
    $env:DD_BENCH_KEY_PASSWORD = $password
    $listed = Invoke-Tool { & (Join-Path $jdk 'bin\keytool.exe') -list -v -keystore $keystore -storepass:env DD_BENCH_KEY_PASSWORD -alias flight-bench }
    $listExit = $LASTEXITCODE
    Remove-Item Env:DD_BENCH_KEY_PASSWORD
    $fingerprint = [string]($listed | Where-Object { $_ -like '*SHA256:*' } | Select-Object -First 1)
    if ($listExit -ne 0 -or -not ($fingerprint -match 'SHA256:\s*([0-9A-Fa-f:]+)')) { throw "The test rig's key could not be read: $keystore" }
    $benchSigner = ($Matches[1] -replace ':', '').ToLower()
} elseif (-not $keystore) {
    $keyDir = Join-Path $env:APPDATA 'DroidDronePC-build'
    $keystore = Join-Path $keyDir 'flight-release.jks'
    $passwordFile = Join-Path $keyDir 'flight-release.password'
    if (-not (Test-Path $keystore)) {
        New-Item -ItemType Directory -Force $keyDir | Out-Null
        $bytes = New-Object byte[] 24
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $password = [Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x'
        Set-Content -Path $passwordFile -Value $password -NoNewline -Encoding ascii
        # the password through the environment, not the command line (other programs can read command lines)
        $env:DD_NEW_KEY_PASSWORD = $password
        Invoke-Tool { & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $keystore -storepass:env DD_NEW_KEY_PASSWORD `
            -keypass:env DD_NEW_KEY_PASSWORD -alias flight -keyalg RSA -keysize 4096 -validity 10000 `
            -dname 'CN=DRAUPNE Flight for DRAUPNE Link' } | Out-Null
        Remove-Item Env:DD_NEW_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
        Write-Host "New signing key: $keystore (back it up: updates must be signed with the same key)"
    }
    $password = (Get-Content $passwordFile -Raw).Trim()
}

# fresh copy of upstream (only what the Flight app needs), then our files over it
if (Test-Path $src) { Remove-Item $src -Recurse -Force }
New-Item -ItemType Directory -Force $src | Out-Null
foreach ($item in 'Common', 'Flight', 'libuvccamera', 'mavlink', 'gradle', 'build.gradle', 'gradle.properties', 'gradlew.bat',
        'local.defaults.properties', 'LICENSE') {
    Copy-Item (Join-Path $upstream $item) (Join-Path $src $item) -Recurse -Force
}
Copy-Item (Join-Path $PSScriptRoot 'overlay\*') $src -Recurse -Force
if ($Bench) {
    # the rig's sources over it, and the line that makes the build type "bench" (bench.gradle)
    Copy-Item (Join-Path $PSScriptRoot 'bench-overlay\Flight') $src -Recurse -Force
    Add-Content -Path (Join-Path $src 'Flight\build.gradle') -Encoding ascii -Value "`napply from: 'bench.gradle' // added by build.ps1 -Bench"
} else {
    # a release is built from a copy that has nothing of the rig, nor of the update test
    foreach ($rigFile in 'Flight\bench.gradle', 'Flight\src\bench', 'Common\src\main\java\de\droiddrone\common\UpdateTestSource.java',
            'Flight\src\release\res\xml\update_test_network.xml') {
        if (Test-Path (Join-Path $src $rigFile)) { throw "The source copy has $rigFile, of the test rig or the update test - it must not be in overlay/" }
    }
    if ($UpdateTest) {
        # the update test's few files over it, and UpdateSource told to ask UpdateTestSource first - in this copy alone
        Copy-Item (Join-Path $PSScriptRoot 'bench-overlay\update-test\*') $src -Recurse -Force
        $rule = Join-Path $src 'Common\src\main\java\de\droiddrone\common\UpdateSource.java'
        $ruleText = [IO.File]::ReadAllText($rule)
        $anchor = '    public static boolean fromReleases(String url) {'
        $added = '        if (UpdateTestSource.takes(url)) return true; // added by build.ps1 -UpdateTest: the update test''s build only'
        $nl = if ($ruleText.Contains("`r`n")) { "`r`n" } else { "`n" }
        $found = ([regex]::Matches($ruleText, [regex]::Escape($anchor))).Count
        if ($found -ne 1 -or $ruleText.Contains('UpdateTestSource')) {
            throw "UpdateSource.java is not as -UpdateTest knows it ('$($anchor.Trim())' $found times): change build.ps1 with it (and UpdateTestSourceTest)"
        }
        [IO.File]::WriteAllText($rule, $ruleText.Replace($anchor, $anchor + $nl + $added))
    }
}
Set-Content -Path (Join-Path $src 'local.properties') -Encoding ascii -Value ("sdk.dir=" + ($sdk -replace '\\', '/'))

$env:JAVA_HOME = $jdk
if ($Bench) {
    # Gradle does not get the release key at all
    $env:DD_FLIGHT_KEYSTORE = $null
    $env:DD_FLIGHT_KEYSTORE_PASSWORD = $null
    $env:DD_BENCH_KEYSTORE = $keystore
    $env:DD_BENCH_KEYSTORE_PASSWORD = $password
} else {
    $env:DD_FLIGHT_KEYSTORE = $keystore
    $env:DD_FLIGHT_KEYSTORE_PASSWORD = $password
}
$task = if ($Bench) { ':Flight:assembleBench' } else { ':Flight:assembleRelease' }
Push-Location $src
try {
    $log = Join-Path $root $(if ($Bench) { 'build\flight-bench-build.log' } elseif ($UpdateTest) { 'build\flight-update-test-build.log' } else { 'build\flight-build.log' })
    Invoke-Tool { & .\gradlew.bat --no-daemon -q $task } | Set-Content -Path $log -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        $hits = Get-Content $log | Select-String -Pattern 'error|What went wrong|failed|exception' -Context 0, 2 | Select-Object -First 20
        # nothing matched (a wrapper download that failed, say): the log's end instead
        if ($hits) { $hits | Write-Host } else { Get-Content $log -Tail 30 | Write-Host }
        throw "Gradle failed ($LASTEXITCODE), full output: $log"
    }
} finally {
    Pop-Location
}
$apk = Get-ChildItem (Join-Path $src $(if ($Bench) { 'Flight\build\outputs\apk\bench' } else { 'Flight\build\outputs\apk\release' })) -Filter '*.apk' | Select-Object -First 1
$version = (Select-String -Path (Join-Path $src 'Flight\build.gradle') -Pattern 'versionName "([^"]+)"').Matches[0].Groups[1].Value
# Before it reaches the output folder: a release with anything of the test rig or the update test in it is an error,
# and so is a rig build that is not one in full (marker, package ...bench, version ...-bench, test-only, the rig's
# key), and an update test's build without its marker or with anything of the rig
$scan = Join-Path $PSScriptRoot 'scan-apk.ps1'
if ($Bench) {
    & $scan -Apk $apk.FullName -Sdk $sdk -Jdk $jdk -Bench -Signer $benchSigner
} elseif ($UpdateTest) {
    & $scan -Apk $apk.FullName -Sdk $sdk -Jdk $jdk -UpdateTest
} else {
    & $scan -Apk $apk.FullName -Sdk $sdk -Jdk $jdk
}
New-Item -ItemType Directory -Force $out | Out-Null
$target = Join-Path $out $(if ($Bench) { "DRAUPNE-Flight-$version-bench.apk" } elseif ($UpdateTest) { "DRAUPNE-Flight-$version-update-test.apk" } else { "DRAUPNE-Flight-$version.apk" })
Copy-Item $apk.FullName $target -Force
$sha = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
Write-Host "$target`nSHA-256 $sha"
