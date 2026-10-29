<#
  Builds DroidDrone Flight for DroidDrone PC: the pinned upstream source (../upstream) with the files in
  overlay/ laid over it. Output: build/flight/DroidDrone-Flight-<version>.apk

  Needs: Android SDK with platforms;android-34, build-tools;34.0.0 and ndk;26.3.11579264 (ANDROID_HOME, or the
  default %LOCALAPPDATA%\Android\Sdk), and a JDK 17 (upstream's Android Gradle plugin 8.1 doesn't run on 21):
  JAVA17_HOME, or the default %LOCALAPPDATA%\DroidDroneBuild\jdk-17*.

  Signing: a release keystore outside this repo. DD_FLIGHT_KEYSTORE / DD_FLIGHT_KEYSTORE_PASSWORD, or the default
  %APPDATA%\DroidDronePC-build\flight-release.jks with its password in flight-release.password next to it (created on
  the first build). Never commit either.
#>
$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 turns anything a program writes to stderr into an error: run tools without that, by exit code
function Invoke-Tool([scriptblock]$command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $saved }
}
$root = Split-Path -Parent $PSScriptRoot
$upstream = Join-Path $root 'upstream'
$src = Join-Path $root 'build\flight-src'
$out = Join-Path $root 'build\flight'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$jdk = if ($env:JAVA17_HOME) { $env:JAVA17_HOME } else {
    (Get-ChildItem (Join-Path $env:LOCALAPPDATA 'DroidDroneBuild') -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName
}
if (-not (Test-Path (Join-Path $sdk 'ndk\26.3.11579264'))) { throw "Android SDK with ndk;26.3.11579264 not found at $sdk" }
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\java.exe'))) { throw 'JDK 17 not found (set JAVA17_HOME)' }
if (-not (Test-Path (Join-Path $upstream 'libuvccamera\build.gradle'))) {
    Invoke-Tool { git -C $upstream submodule update --init libuvccamera }
}

# signing key: created once, kept outside the repo
$keystore = $env:DD_FLIGHT_KEYSTORE
$password = $env:DD_FLIGHT_KEYSTORE_PASSWORD
if (-not $keystore) {
    $keyDir = Join-Path $env:APPDATA 'DroidDronePC-build'
    $keystore = Join-Path $keyDir 'flight-release.jks'
    $passwordFile = Join-Path $keyDir 'flight-release.password'
    if (-not (Test-Path $keystore)) {
        New-Item -ItemType Directory -Force $keyDir | Out-Null
        $bytes = New-Object byte[] 24
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $password = [Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x'
        Set-Content -Path $passwordFile -Value $password -NoNewline -Encoding ascii
        Invoke-Tool { & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $keystore -storepass $password -keypass $password `
            -alias flight -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=DroidDrone Flight for DroidDrone PC' } | Out-Null
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
Set-Content -Path (Join-Path $src 'local.properties') -Encoding ascii -Value ("sdk.dir=" + ($sdk -replace '\\', '/'))

$env:JAVA_HOME = $jdk
$env:DD_FLIGHT_KEYSTORE = $keystore
$env:DD_FLIGHT_KEYSTORE_PASSWORD = $password
Push-Location $src
try {
    $log = Join-Path $root 'build\flight-build.log'
    Invoke-Tool { & .\gradlew.bat --no-daemon -q ':Flight:assembleRelease' } | Set-Content -Path $log -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        Get-Content $log | Select-String -Pattern 'error|What went wrong|failed' -Context 0, 2 | Select-Object -First 20 | Write-Host
        throw "Gradle failed ($LASTEXITCODE), full output: $log"
    }
} finally {
    Pop-Location
}
$apk = Get-ChildItem (Join-Path $src 'Flight\build\outputs\apk\release') -Filter '*.apk' | Select-Object -First 1
$version = (Select-String -Path (Join-Path $src 'Flight\build.gradle') -Pattern 'versionName "([^"]+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force $out | Out-Null
$target = Join-Path $out "DroidDrone-Flight-$version.apk"
Copy-Item $apk.FullName $target -Force
$sha = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
Write-Host "$target`nSHA-256 $sha"
