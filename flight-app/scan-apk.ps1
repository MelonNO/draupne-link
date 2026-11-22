<#
  Is this APK the test rig's build of DRAUPNE Flight (bench-overlay/)? That build must never be released.

    scan-apk.ps1 -Apk <file>           the APK must have NOTHING of the rig; an error says what it has
    scan-apk.ps1 -Apk <file> -Bench    the APK must be the rig's build in full; an error says what is missing

  Read from the file itself, with no tool: the marker DRAUPNE-BENCH-FC in any of its parts (code, manifest,
  resources), and in its manifest ".bench" (the package name), "-bench" (the version), the test-only flag and the
  rig's control receiver.
  With an Android SDK also asked of aapt (package, version, label, test-only) and of apksigner (who signed it: the
  rig's key says BENCH in its name). Given -Sdk these two must answer; without it they are used when ANDROID_HOME
  has them, and a tool that is missing or fails is only remarked - the file's own bytes decide.

  Run by flight-app\build.ps1 on every APK it builds, before the APK reaches its output folder, and by the release
  workflow on the phone app it publishes.
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [switch]$Bench,
    [string]$Sdk,
    [string]$Jdk,
    # -Bench: the SHA-256 of the certificate that must have signed it (the rig's key)
    [string]$Signer
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$marker = 'DRAUPNE-BENCH-FC'
$inManifest = '.bench', '-bench', 'testOnly', 'BenchControlReceiver'
$file = (Resolve-Path $Apk).Path
$name = Split-Path $file -Leaf

# every byte a letter, so text is found as it lies in the file: one byte a letter (code, resources) or two (a manifest).
# Letter for letter, small and capital told apart: the released app's manifest has AndroidX's
# "androidx.profileinstaller.action.BENCHMARK_OPERATION", which is not ".bench"
$bytesAsText = [Text.Encoding]::GetEncoding(28591)
function Test-Has([string]$text, [string]$word) {
    $wide = -join ($word.ToCharArray() | ForEach-Object { "$_$([char]0)" })
    return ($text.IndexOf($word, [StringComparison]::Ordinal) -ge 0) -or ($text.IndexOf($wide, [StringComparison]::Ordinal) -ge 0)
}

$markerIn = @()
$manifestHas = @()
$parts = 0
$manifestSeen = $false
$zip = [IO.Compression.ZipFile]::OpenRead($file)
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.Length -eq 0) { continue }
        $parts++
        $memory = New-Object IO.MemoryStream
        $stream = $entry.Open()
        try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
        $text = $bytesAsText.GetString($memory.ToArray())
        $memory.Dispose()
        if (Test-Has $text $marker) { $markerIn += $entry.FullName }
        if ($entry.FullName -eq 'AndroidManifest.xml') {
            $manifestSeen = $true
            foreach ($word in $inManifest) { if (Test-Has $text $word) { $manifestHas += $word } }
        }
    }
} finally {
    $zip.Dispose()
}
if (-not $manifestSeen) { throw "${name}: no AndroidManifest.xml in it - not an APK" }

# ---- the SDK's tools, when there are any
$strict = [bool]$Sdk
$sdkDir = if ($Sdk) { $Sdk } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
$tools = $null
if ($sdkDir -and (Test-Path (Join-Path $sdkDir 'build-tools'))) {
    $tools = Get-ChildItem (Join-Path $sdkDir 'build-tools') -Directory | Sort-Object Name -Descending |
        Where-Object { (Test-Path (Join-Path $_.FullName 'aapt.exe')) -or (Test-Path (Join-Path $_.FullName 'aapt')) } | Select-Object -First 1
}
function Invoke-Quiet([scriptblock]$command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $saved }
}
$remarks = @()
$package = $null; $version = $null; $label = $null; $testOnly = $null; $signerName = $null; $signerSha = $null
if ($tools) {
    $aapt = Join-Path $tools.FullName 'aapt.exe'
    if (-not (Test-Path $aapt)) { $aapt = Join-Path $tools.FullName 'aapt' }
    $badging = Invoke-Quiet { & $aapt dump badging $file }
    $aaptExit = $LASTEXITCODE
    $line = [string]($badging | Where-Object { $_ -like 'package: name=*' } | Select-Object -First 1)
    if ($aaptExit -eq 0 -and $line -match "^package: name='([^']*)' versionCode='[^']*' versionName='([^']*)'") {
        $package = $Matches[1]; $version = $Matches[2]
        $labelLine = [string]($badging | Where-Object { $_ -like 'application-label:*' } | Select-Object -First 1)
        if ($labelLine -match "^application-label:'(.*)'$") { $label = $Matches[1] }
        $testOnly = [bool]($badging | Where-Object { $_ -like "testOnly='-1'*" })
    } else {
        $remarks += "aapt did not read it ($aaptExit)"
    }
    $jar = Join-Path $tools.FullName 'lib\apksigner.jar'
    $java = if ($Jdk) { Join-Path $Jdk 'bin\java.exe' } elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    if (Test-Path $jar) {
        $certs = @()
        try { $certs = Invoke-Quiet { & $java -jar $jar verify --print-certs $file } } catch { $certs = @("$_") }
        # apksigner words its lines by version: "Signer #1 certificate DN: ..." (build-tools 34) or
        # "V2 Signer: certificate DN: ..." (newer, as on the CI runner) - both are read, the first one found counts
        $dnLine = '^(?:Signer #1|V\d+ Signer):? certificate DN: (.*)$'
        $shaLine = '^(?:Signer #1|V\d+ Signer):? certificate SHA-256 digest: ([0-9a-fA-F]+)'
        $dn = [string]($certs | Where-Object { $_ -match $dnLine } | Select-Object -First 1)
        if ($dn -match $dnLine) { $signerName = $Matches[1] }
        $sha = [string]($certs | Where-Object { $_ -match $shaLine } | Select-Object -First 1)
        if ($sha -match $shaLine) { $signerSha = $Matches[1].ToLower() }
        if (-not $signerName -or -not $signerSha) { $remarks += "apksigner named no signer ($(($certs | Select-Object -First 1)))" }
    } else {
        $remarks += 'no apksigner.jar beside aapt'
    }
} else {
    $remarks += 'no Android SDK build-tools found: aapt and apksigner were not asked'
}
if ($strict -and $remarks) { throw "${name}: $($remarks -join '; ') (asked for with -Sdk $Sdk)" }

# ---- the verdict
$wrong = @()
if ($Bench) {
    if (-not ($markerIn | Where-Object { $_ -like '*.dex' })) { $wrong += "the marker $marker is not in its code" }
    if ($markerIn -notcontains 'AndroidManifest.xml') { $wrong += "the marker $marker is not in its manifest" }
    foreach ($word in $inManifest) { if ($manifestHas -notcontains $word) { $wrong += "its manifest has no $word" } }
    if ($null -ne $package -and $package -notlike '*.bench') { $wrong += "its package is $package, not ...bench" }
    if ($null -ne $version -and $version -notlike '*-bench') { $wrong += "its version is $version, not ...-bench" }
    if ($null -ne $testOnly -and -not $testOnly) { $wrong += 'it is not test-only' }
    if ($null -ne $label -and $label -notlike '*BENCH*') { $wrong += "its name on the phone is '$label', without BENCH" }
    if ($null -ne $signerName -and $signerName -notlike '*BENCH*') { $wrong += "it is signed by '$signerName', not by the rig's key" }
    if ($Signer -and $signerSha -ne $Signer.ToLower()) { $wrong += "it is signed by $signerSha, not by the rig's key $($Signer.ToLower())" }
    if ($wrong) { throw "${name} is NOT the test rig's build in full: $($wrong -join '; ')" }
    $what = "the test rig's build - NEVER RELEASE IT (marker in $($markerIn -join ', '))"
} else {
    if ($markerIn) { $wrong += "the marker $marker is in $($markerIn -join ', ')" }
    foreach ($word in $manifestHas) { $wrong += "its manifest has $word" }
    if ($null -ne $package -and $package -match '\.bench') { $wrong += "its package is $package" }
    if ($null -ne $version -and $version -match '-bench') { $wrong += "its version is $version" }
    if ($testOnly) { $wrong += 'it is test-only' }
    if ($null -ne $signerName -and $signerName -match 'BENCH') { $wrong += "it is signed by the rig's key ($signerName)" }
    if ($wrong) { throw "${name} HAS THE TEST RIG'S CODE OR NAME AND MUST NOT BE RELEASED: $($wrong -join '; ')" }
    $what = "nothing of the test rig (marker $marker in none of its $parts parts)"
}
$facts = @()
if ($package) { $facts += "package $package"; $facts += "version $version"; $facts += "name '$label'" }
if ($null -ne $testOnly) { $facts += $(if ($testOnly) { 'test-only' } else { 'not test-only' }) }
if ($signerSha) { $facts += "signed by $signerName, SHA-256 $signerSha" }
Write-Host "scan-apk: ${name}: $what$(if ($facts) { '; ' + ($facts -join ', ') })$(if ($remarks) { ' [' + ($remarks -join '; ') + ']' })"
