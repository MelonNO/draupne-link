<#
.SYNOPSIS
The installer test: a new DRAUPNE Link installed over the released one the way UPDATE installs it, the user's files
checked before and after, and the way back. By hand, on a PC where a released DRAUPNE Link is installed and nothing
flies; from a normal (not administrator) PowerShell - Windows asks once, as for UPDATE.

.DESCRIPTION
One mode a run:
  -Check                        Read only: what is installed, what a run would copy aside, the PC's state. Writes nothing.
  -Installer <setup.exe>        The upgrade, in steps:
                                (a) refuses unless DRAUPNE Link -Expect (1.0.69) is installed, as released
                                (b) copies aside - never moves or deletes - the app's folders, with a hash list
                                (c) closes the running app only if it runs (never by force, never with a link open)
                                (d) runs the installer as UPDATE's helper does (/passive /norestart), its log beside
                                (e) checks: the new version installed, the old one gone, every user file as it was,
                                    the firewall rules (the link port's narrowed), the Start menu, ProgramData's rights
                                (f) starts the new app once (-AppSeconds, 20), reads its own log, closes it, checks again
                                (g) says how to go back
  -Back [-From <run folder>]    Back to the released 1.0.69: its installer from the project's GitHub release (or
                                -BackInstaller <file>), checked against its SHA-256, run over whatever is installed
                                (jpackage's installers take a downgrade), then the settings copied back from the run
                                folder -From (the newest upgrade run's when left out). Copies aside first.
  -Uninstall                    Removes the installed DRAUPNE Link (Windows' own uninstall), after a copy aside. What it
                                removes is printed first (Get-Help -Full: UNINSTALL).

Every run but -Check writes into a folder of its own: <Root>\<date-time>-<mode>, Root being
%LOCALAPPDATA%\DraupneBench\installer-test unless -Root says otherwise: the copy aside (copy\<group>\...), the hash
lists (hashes-*.tsv), what changed (changes-*.tsv), the PC's state at each step (state-*.json), the installer's log
(msi-*.log), transcript.txt and summary.txt.

The script itself never moves or deletes a file of the PC's, never ends a program by force, never writes the registry,
and never starts anything but the installer it is given (or 1.0.69's, -Back), Windows' own msiexec (-Uninstall) and,
in step (f), the installed DRAUPNE Link. Exit code: 0 every check passed, 1 a check failed, 2 refused or stopped before
anything was installed, 3 stopped after the installer ran.

UNINSTALL (-Uninstall) - Windows' uninstall of DRAUPNE Link (msiexec /x <its product code> /passive /norestart):
  removes  the program folder (Program Files\DRAUPNE Link: DRAUPNE Link.exe, Virtual fleet.bat, app\, runtime\),
           the Start menu entry (all users: Start Menu\Programs\DRAUPNE Link), the desktop shortcut the installer
           made (the all-users desktop's), the entry in Windows' installed apps, and - as administrator, the app's
           cleanup-pc - the firewall rules DRAUPNE-Link-UDP and DRAUPNE-Link-PhoneDownload-TCP (DroidDrone PC's two
           old ones too) and a leftover scheduled task "DRAUPNE Link - install Tailscale"
  keeps    settings, logs, map tiles (%APPDATA%\DraupneLink), flights, Overwatch clips, Betaflight backups
           (Documents\DRAUPNE Link), recordings (Videos\DRAUPNE Link), the installer's log (ProgramData\DraupneLink,
           one line more), Tailscale, the power plan, and the shortcuts the app made for this user itself (they then
           lead nowhere)

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File tools\installer-test.ps1 -Check -Installer build\release\DRAUPNE-Link-Setup-1.0.72.exe
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File tools\installer-test.ps1 -Installer build\release\DRAUPNE-Link-Setup-1.0.72.exe
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File tools\installer-test.ps1 -Back
#>
[CmdletBinding()]
param(
    [string]$Installer,
    [string]$Expect = '1.0.69',
    [switch]$Check,
    [switch]$Back,
    [string]$BackInstaller,
    [string]$From,
    [switch]$Uninstall,
    [int]$AppSeconds = 20,
    [switch]$NoAppStart,
    [string]$Root,
    [switch]$AllowElevated,
    [switch]$LoadOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# ---- the app and its installer, by the names the app's code gives them (InstallerTestScriptTest holds them together)
$script:AppName = 'DRAUPNE Link'
$script:AppExe = 'DRAUPNE Link.exe'
$script:OldExe = 'DroidDrone PC.exe'                              # its name up to 1.0.15; the installer waits for it too
$script:Vendor = 'draupne-link'                                   # build.gradle's --vendor: part of the product code
$script:UpgradeCode = '{5D2F1D3E-6A0B-4C9E-9F53-2B7C1A8E4D61}'    # build.gradle's --win-upgrade-uuid, PcPrep.UPGRADE_CODE
$script:LinkRule = 'DRAUPNE-Link-UDP'                             # WindowsSetup.LINK_RULE
$script:ApkRule = 'DRAUPNE-Link-PhoneDownload-TCP'                # WindowsSetup.APK_RULE
$script:OldRules = @('DroidDronePC-Link-UDP', 'DroidDronePC-PhoneDownload-TCP') # WindowsSetup.OLD_RULES
$script:RuleRemote = @('LocalSubnet', '100.64.0.0/10')            # WindowsSetup.RULE_REMOTE: both rules, 1.0.72 on
$script:NarrowedFrom = '1.0.72'                                   # the link port's rule was open to any address before
$script:TailscaleTask = 'DRAUPNE Link - install Tailscale'        # PcPrep.TAILSCALE_TASK
$script:SidSystem = 'S-1-5-18'                                    # PcPrep.SID_SYSTEM
$script:SidAdmins = 'S-1-5-32-544'                                # PcPrep.SID_ADMINISTRATORS
$script:SidUsers = 'S-1-5-32-545'                                 # PcPrep.SID_USERS
$script:SettingsFile = 'draupne-link.properties'                  # PcConfig.FILE_NAME
$script:AppLogsKept = 14                                          # AppLog.KEEP_FILES: older app logs go at every start
$script:UpdateArgs = @('/passive', '/norestart')                  # what UPDATE's helper runs the installer with (SelfUpdate)
$script:Downloads = 'https://github.com/MelonNO/draupne-link/releases/download/' # UpdateCheck.DOWNLOADS
$script:ReleaseApi = 'https://api.github.com/repos/MelonNO/draupne-link/releases/tags/'
# The released installers -Back puts back, as the project's GitHub release lists them (name, size, digest); the
# download is checked against these and against what GitHub says again at the time.
$script:Released = @{
    '1.0.69' = @{ Name = 'DRAUPNE-Link-Setup-1.0.69.exe'; Bytes = 96783360; Sha256 = 'cf940f211b36c4f7c09df4f7b77b19f897fb664b9730425ba35702ecc076967f' }
}
$script:BackTo = '1.0.69'
# Files -Back copies back over a changed one (the one there kept aside first): the settings. Any file missing is copied back.
$script:SettingsFiles = @(
    @{ Group = 'appdata'; Path = 'draupne-link.properties' }, @{ Group = 'appdata'; Path = 'virtual-fleet.txt' },
    @{ Group = 'appdata'; Path = 'last-known.txt' }, @{ Group = 'appdata-old'; Path = 'droiddrone-pc.properties' },
    @{ Group = 'documents'; Path = 'pins.txt' }, @{ Group = 'documents'; Path = 'graphics.kml' })

$script:Checks = [System.Collections.Generic.List[object]]::new()
$script:Run = $null
$script:Self = $PSCommandPath
$script:Stopped = $false    # the run went on after an installer failed, or the app would not close: exit 3
$script:Installed = $false  # an installer (or the uninstall) was started: from here on nothing is "refused"
$script:Refused = $false    # -Check: step (a) would refuse

function Say([string]$Text) {
    Write-Host ((Get-Date -Format 'HH:mm:ss') + '  ' + $Text)
}

function Add-Check([string]$Name, [string]$Verdict, [string]$Detail) {
    $script:Checks.Add([pscustomobject]@{ Check = $Name; Verdict = $Verdict; Detail = $Detail })
    Say ("{0,-5} {1}: {2}" -f $Verdict, $Name, $Detail)
}

function Get-Prop($Object, [string]$Name) {
    $p = $Object.PSObject.Properties[$Name]
    if ($p) { return $p.Value }
    return $null
}

# ---------------------------------------------------------------------------------------------------- versions, codes

<# jpackage's product code for a version (JDK 21, WinMsiBundler.getProductCode): a name-based UUID of
   "ProductCode/<vendor>/<name>/<version>" - every build of one version has the same. #>
function Get-ProductCode([string]$Version) {
    $md5 = [Security.Cryptography.MD5]::Create()
    try {
        $b = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes("ProductCode/$script:Vendor/$script:AppName/$Version"))
    } finally {
        $md5.Dispose()
    }
    $b[6] = ($b[6] -band 0x0f) -bor 0x30
    $b[8] = ($b[8] -band 0x3f) -bor 0x80
    $h = [BitConverter]::ToString($b).Replace('-', '')
    return '{' + $h.Substring(0, 8) + '-' + $h.Substring(8, 4) + '-' + $h.Substring(12, 4) + '-' + $h.Substring(16, 4) + '-' + $h.Substring(20, 12) + '}'
}

<# Numbers compared in order, as the app's UpdateCheck.isNewer: 1, 0 or -1. #>
function Compare-Version([string]$A, [string]$B) {
    $x = @([regex]::Matches($A, '\d+') | ForEach-Object { [long]$_.Value })
    $y = @([regex]::Matches($B, '\d+') | ForEach-Object { [long]$_.Value })
    for ($i = 0; $i -lt [Math]::Max($x.Count, $y.Count); $i++) {
        $p = 0; if ($i -lt $x.Count) { $p = $x[$i] }
        $q = 0; if ($i -lt $y.Count) { $q = $y[$i] }
        if ($p -ne $q) { if ($p -gt $q) { return 1 } else { return -1 } }
    }
    return 0
}

# ---------------------------------------------------------------------------------------------------- where things are

<# This PC's folders, as the app finds them (Java's user.home is %USERPROFILE%, its temp folder Windows' own). #>
function Get-PcFolders {
    @{
        AppData = $env:APPDATA; LocalAppData = $env:LOCALAPPDATA; Home = $env:USERPROFILE
        Temp = [IO.Path]::GetTempPath().TrimEnd('\'); ProgramData = $env:ProgramData; ProgramFiles = $env:ProgramFiles
        CommonDesktop = [Environment]::GetFolderPath('CommonDesktopDirectory'); Desktop = [Environment]::GetFolderPath('Desktop')
    }
}

function New-Place([string]$Group, [string]$Kind, [string]$Path, [string]$What, [string]$Only) {
    [pscustomobject]@{ Group = $Group; Kind = $Kind; Path = $Path.TrimEnd('\'); What = $What; Only = $Only }
}

<#
Every place the app or its installer keeps a file, a group each. Kind: user (the pilot's files: none may be lost or
changed but those the app is known to change), installer (the installer's own: checked on their own), program (the
installed program: replaced by the installer). Only: one file of that name in the folder, no subfolders.
#>
function Get-Places($F) {
    $startMenu = Join-Path $F.ProgramData 'Microsoft\Windows\Start Menu\Programs'
    $ownStartMenu = Join-Path $F.AppData 'Microsoft\Windows\Start Menu\Programs'
    @(
        (New-Place 'appdata' 'user' (Join-Path $F.AppData 'DraupneLink') 'settings (draupne-link.properties), logs (app-*.log, link-*.csv), map tiles, airspace, cell towers, last places, the phone app download, ESC backups')
        (New-Place 'appdata-old' 'user' (Join-Path $F.AppData 'DroidDronePC') "DroidDrone PC's settings and logs (up to 1.0.15; read once when draupne-link.properties is missing)")
        (New-Place 'documents' 'user' (Join-Path $F.Home 'Documents\DRAUPNE Link') 'flights (Tracks), Overwatch clips, Betaflight backups, Exports, pins, map graphics')
        (New-Place 'videos' 'user' (Join-Path $F.Home 'Videos\DRAUPNE Link') 'recorded flights')
        (New-Place 'update' 'user' (Join-Path $F.Temp 'DraupneLink-update') "UPDATE's download folder, its helper's log")
        (New-Place 'programdata' 'installer' (Join-Path $F.ProgramData 'DraupneLink') "the installer's log (setup.log)")
        (New-Place 'startmenu' 'installer' (Join-Path $startMenu $script:AppName) "the installer's Start menu entry (all users)")
        (New-Place 'desktop-all' 'installer' $F.CommonDesktop "the installer's desktop shortcut (all users)" "$script:AppName.lnk")
        (New-Place 'desktop-own' 'installer' $F.Desktop "this user's desktop shortcut (the app makes one when there is none)" "$script:AppName.lnk")
        (New-Place 'startmenu-own' 'installer' $ownStartMenu "this user's Start menu shortcut (the app's, when the installer's is missing)" "$script:AppName.lnk")
        (New-Place 'program' 'program' (Join-Path $F.ProgramFiles $script:AppName) 'the installed program')
    )
}

function Get-InstalledExe($F) {
    return Join-Path (Join-Path $F.ProgramFiles $script:AppName) $script:AppExe
}

# ---------------------------------------------------------------------------------------------------- files and hashes

function Get-Sha256([string]$Path, $Sha) {
    try {
        $s = New-Object IO.FileStream($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        try {
            return [BitConverter]::ToString($Sha.ComputeHash($s)).Replace('-', '').ToLowerInvariant()
        } finally {
            $s.Dispose()
        }
    } catch {
        return 'UNREADABLE'
    }
}

<#
A place's files: group, path inside it, bytes, SHA-256, last change (UTC). Links (junctions, symbolic links) are
listed as LINK and never followed. A folder that is not there has no files. -NoHash: sizes only (-Check).
#>
function Get-FileList($Place, [switch]$NoHash) {
    $rows = [System.Collections.Generic.List[object]]::new()
    if (-not (Test-Path -LiteralPath $Place.Path -PathType Container)) { return , $rows }
    $root = (New-Object IO.DirectoryInfo $Place.Path).FullName.TrimEnd('\')
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $stack = [System.Collections.Generic.Stack[IO.DirectoryInfo]]::new()
        $stack.Push((New-Object IO.DirectoryInfo $root))
        while ($stack.Count -gt 0) {
            $dir = $stack.Pop()
            try {
                $items = @($dir.GetFileSystemInfos())
            } catch {
                $rel = '.'; if ($dir.FullName.Length -gt $root.Length) { $rel = $dir.FullName.Substring($root.Length + 1) }
                $rows.Add([pscustomobject]@{ Group = $Place.Group; Path = $rel + '\'; Bytes = 0; Sha256 = 'UNREADABLE'; Modified = '' })
                continue
            }
            foreach ($item in $items) {
                $link = ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
                $rel = $item.FullName.Substring($root.Length + 1)
                if ($item -is [IO.DirectoryInfo]) {
                    if ($Place.Only) { continue }
                    if ($link) { $rows.Add([pscustomobject]@{ Group = $Place.Group; Path = $rel + '\'; Bytes = 0; Sha256 = 'LINK'; Modified = '' }) }
                    else { $stack.Push($item) }
                    continue
                }
                if ($Place.Only -and $item.Name -ne $Place.Only) { continue }
                $hash = 'LINK'
                if (-not $link) { if ($NoHash) { $hash = '' } else { $hash = Get-Sha256 $item.FullName $sha } }
                $rows.Add([pscustomobject]@{ Group = $Place.Group; Path = $rel; Bytes = $item.Length; Sha256 = $hash
                        Modified = $item.LastWriteTimeUtc.ToString('yyyy-MM-ddTHH:mm:ss.fffZ', [Globalization.CultureInfo]::InvariantCulture) })
            }
        }
    } finally {
        $sha.Dispose()
    }
    return , $rows
}

function Get-FileLists($Places, [switch]$NoHash) {
    $all = [System.Collections.Generic.List[object]]::new()
    foreach ($p in $Places) { foreach ($r in (Get-FileList $p -NoHash:$NoHash)) { $all.Add($r) } }
    return , $all
}

function Write-HashList($Rows, [string]$File) {
    $w = New-Object IO.StreamWriter($File, $false, (New-Object Text.UTF8Encoding($false)))
    try {
        $w.WriteLine("group`tpath`tbytes`tsha256`tmodifiedUtc")
        foreach ($r in $Rows) { $w.WriteLine((@($r.Group, $r.Path, $r.Bytes, $r.Sha256, $r.Modified) -join "`t")) }
    } finally {
        $w.Dispose()
    }
}

function Read-HashList([string]$File) {
    $rows = [System.Collections.Generic.List[object]]::new()
    $first = $true
    foreach ($line in [IO.File]::ReadAllLines($File, [Text.Encoding]::UTF8)) {
        if ($first) { $first = $false; continue }
        if (-not $line) { continue }
        $c = $line.Split("`t")
        $rows.Add([pscustomobject]@{ Group = $c[0]; Path = $c[1]; Bytes = [long]$c[2]; Sha256 = $c[3]; Modified = $c[4] })
    }
    return , $rows
}

<#
What changed from one list to the other: missing, changed (bytes or SHA-256) or new, each with its verdict - FAIL for a
file missing or changed that no rule allows, EXPECTED for one a rule allows (its why), NEW for a file that came.
A rule: @{ Group; Like (a wildcard on the path) or Paths (exact paths); Change = missing | changed | new | any; Why }.
#>
function Compare-HashList($Before, $After, $Allowed) {
    $b = @{}; foreach ($r in $Before) { $b[$r.Group + '|' + $r.Path] = $r }
    $a = @{}; foreach ($r in $After) { $a[$r.Group + '|' + $r.Path] = $r }
    $out = [System.Collections.Generic.List[object]]::new()
    foreach ($k in $b.Keys) {
        $r = $b[$k]
        if (-not $a.ContainsKey($k)) { $out.Add((New-Change $r 'missing' $Allowed)); continue }
        $n = $a[$k]
        if ($n.Sha256 -ne $r.Sha256 -or $n.Bytes -ne $r.Bytes) { $out.Add((New-Change $r 'changed' $Allowed)) }
    }
    foreach ($k in $a.Keys) { if (-not $b.ContainsKey($k)) { $out.Add((New-Change $a[$k] 'new' $Allowed)) } }
    return , @($out | Sort-Object Group, Path)
}

function New-Change($Row, [string]$Change, $Allowed) {
    $verdict = 'FAIL'; $why = ''
    if ($Change -eq 'new') { $verdict = 'NEW' }
    foreach ($rule in @($Allowed)) {
        if ($rule.Group -ne $Row.Group) { continue }
        if ($rule.Change -ne 'any' -and $rule.Change -ne $Change) { continue }
        if ($rule.ContainsKey('Paths')) { if (-not (@($rule.Paths) -contains $Row.Path)) { continue } }
        elseif ($Row.Path -notlike $rule.Like) { continue }
        $verdict = 'EXPECTED'; $why = $rule.Why
        break
    }
    [pscustomobject]@{ Group = $Row.Group; Path = $Row.Path; Change = $Change; Verdict = $verdict; Why = $why }
}

<#
The changes to the user's files that are no fault, by stage: none for the installer (install, uninstall: it writes
no user file); for the app's start (app) the ones its code makes at every start and exit. $Before: the list the
start begins from (the app logs it keeps are counted from it).
#>
function Get-ExpectedChanges([string]$Stage, $Before) {
    $rules = [System.Collections.Generic.List[object]]::new()
    if ($Stage -ne 'app') { return , $rules }
    $rules.Add(@{ Group = 'appdata'; Like = $script:SettingsFile; Change = 'changed'; Why = 'the app writes its settings (window place, kept flights, ...): their keys are compared on their own' })
    $rules.Add(@{ Group = 'appdata'; Like = 'last-known.txt'; Change = 'any'; Why = "every phone's last place, written at the exit (LastKnown)" })
    $rules.Add(@{ Group = 'appdata'; Like = 'logs\app-*'; Change = 'changed'; Why = "today's app log, added to" })
    foreach ($cache in 'tiles', 'airspace', 'opencellid') {
        $rules.Add(@{ Group = 'appdata'; Like = "$cache\*"; Change = 'changed'; Why = "the map's own cache ($cache), filled and refreshed by the app" })
    }
    # AppLog.prune, at every start before today's log is opened (1.0.69 the same): the 14 newest "app-*" in logs stay
    $names = [string[]]@($Before | Where-Object { $_.Group -eq 'appdata' -and $_.Path -like 'logs\app-*' -and $_.Path.IndexOf('\', 5) -lt 0 } | ForEach-Object { $_.Path })
    [Array]::Sort($names, [StringComparer]::OrdinalIgnoreCase)
    [Array]::Reverse($names)
    if ($names.Count -gt $script:AppLogsKept) {
        $rules.Add(@{ Group = 'appdata'; Paths = @($names[$script:AppLogsKept..($names.Count - 1)]); Change = 'missing'; Why = "the app keeps its $script:AppLogsKept newest app logs (AppLog, at every start - 1.0.69 too)" })
    }
    $rules.Add(@{ Group = 'update'; Like = 'update.log'; Change = 'missing'; Why = "the app says UPDATE's result once and renames update.log to update-last.log" })
    $rules.Add(@{ Group = 'update'; Like = 'update-last.log'; Change = 'changed'; Why = 'the same' })
    return , $rules
}

function Write-Changes($Changes, [string]$File) {
    $lines = @("verdict`tchange`tgroup`tpath`twhy") + @($Changes | ForEach-Object { (@($_.Verdict, $_.Change, $_.Group, $_.Path, $_.Why) -join "`t") })
    [IO.File]::WriteAllLines($File, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
}

<# Adds the check for a compare: FAIL with the first ten that fail; the expected and new ones counted. #>
function Add-CompareCheck([string]$Name, $Changes, [string]$File) {
    if ($File) { Write-Changes $Changes $File }
    $fail = @($Changes | Where-Object { $_.Verdict -eq 'FAIL' })
    $expected = @($Changes | Where-Object { $_.Verdict -eq 'EXPECTED' })
    $new = @($Changes | Where-Object { $_.Verdict -eq 'NEW' })
    $tail = "$($expected.Count) expected, $($new.Count) new"
    if ($File) { $tail += ' (' + (Split-Path $File -Leaf) + ')' }
    if ($fail.Count) {
        $list = ($fail | Select-Object -First 10 | ForEach-Object { "$($_.Change) $($_.Group)\$($_.Path)" }) -join '; '
        Add-Check $Name 'FAIL' "$($fail.Count) lost or changed: $list; $tail"
    } else {
        Add-Check $Name 'PASS' "no user file lost or changed; $tail"
    }
    foreach ($e in @($expected | Group-Object Why)) { Say ("        expected: $($e.Count) x $($e.Name)") }
}

# ---------------------------------------------------------------------------------------------------- settings file

<# The settings file's keys and values, read as Java's Properties reads the lines the app writes. #>
function Read-Settings([string]$File) {
    $map = [System.Collections.Hashtable]::new()  # case-sensitive, as Java's keys
    if (-not $File -or -not (Test-Path -LiteralPath $File)) { return $map }
    $pending = ''
    foreach ($raw in [IO.File]::ReadAllLines($File, [Text.Encoding]::UTF8)) {
        $line = $raw.TrimStart()
        if (-not $pending -and ($line -eq '' -or $line.StartsWith('#') -or $line.StartsWith('!'))) { continue }
        $line = $pending + $line
        $tail = [regex]::Match($line, '\\+$').Length
        if ($tail % 2 -eq 1) { $pending = $line.Substring(0, $line.Length - 1); continue }
        $pending = ''
        $m = [regex]::Match($line, '^((?:\\.|[^=:\s\\])*)\s*[=:\s]?\s*(.*)$')
        if ($m.Success) { $map[$m.Groups[1].Value] = $m.Groups[2].Value }
    }
    return $map
}

<# Which keys went, came or changed (their names only: values stay in the files). #>
function Compare-Settings($Before, $After) {
    [pscustomobject]@{
        Removed = @($Before.Keys | Where-Object { -not $After.ContainsKey($_) } | Sort-Object)
        Added   = @($After.Keys | Where-Object { -not $Before.ContainsKey($_) } | Sort-Object)
        Changed = @($Before.Keys | Where-Object { $After.ContainsKey($_) -and $After[$_] -cne $Before[$_] } | Sort-Object)
    }
}

function Add-SettingsCheck([string]$Name, [string]$BeforeFile, [string]$AfterFile) {
    $before = Read-Settings $BeforeFile
    $after = Read-Settings $AfterFile
    if ($before.Count -eq 0) { Add-Check $Name 'NOTE' 'no settings file before'; return }
    $d = Compare-Settings $before $after
    # MainWindow: the OUT OF ACTION marks of 1.0.70 and before (drone.crashed) move to drone.outOfAction when next saved
    $lost = @($d.Removed | Where-Object { $_ -ne 'drone.crashed' })
    $detail = "$($before.Count) keys before, $($after.Count) after; changed: " + (($d.Changed) -join ', ') + '; added: ' + (($d.Added) -join ', ')
    if ($lost.Count) { Add-Check $Name 'FAIL' ("keys gone: " + ($lost -join ', ') + "; $detail") }
    else { Add-Check $Name 'PASS' "every key still there; $detail" }
}

# ---------------------------------------------------------------------------------------------------- what is installed

function Get-UninstallEntries {
    $keys = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
            'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
            'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
    @(Get-ItemProperty -Path $keys -ErrorAction SilentlyContinue | Where-Object {
            $n = Get-Prop $_ 'DisplayName'; $n -eq $script:AppName -or $n -eq 'DroidDrone PC' } | ForEach-Object {
            [pscustomobject]@{ Code = [string]$_.PSChildName; Name = [string](Get-Prop $_ 'DisplayName'); Version = [string](Get-Prop $_ 'DisplayVersion')
                Location = [string](Get-Prop $_ 'InstallLocation'); Key = ([string]$_.PSPath) -replace '^.*::', '' } })
}

<# Why the installed DRAUPNE Link is not the one expected, or $null when it is: one entry, that version, jpackage's
   product code for it, in Program Files, for all users, and no DroidDrone PC beside it. #>
function Test-Installed($Entries, [string]$Expect, [string]$ProgramFiles) {
    $all = @($Entries)
    $old = @($all | Where-Object { $_.Name -ne $script:AppName })
    $mine = @($all | Where-Object { $_.Name -eq $script:AppName })
    if ($old.Count) { return "DroidDrone PC $($old[0].Version) is installed too" }
    if ($mine.Count -eq 0) { return "DRAUPNE Link is not installed (expected $Expect)" }
    if ($mine.Count -gt 1) { return "DRAUPNE Link is installed $($mine.Count) times (" + (($mine | ForEach-Object { $_.Version }) -join ', ') + "), expected once ($Expect)" }
    $e = $mine[0]
    if ($e.Version -ne $Expect) { return "DRAUPNE Link $($e.Version) is installed, not $Expect" }
    $code = Get-ProductCode $Expect
    if ($e.Code -ne $code) { return "DRAUPNE Link $Expect is installed with product code $($e.Code), not $code (jpackage's for $Expect)" }
    $where = Join-Path $ProgramFiles $script:AppName
    if ($e.Location.TrimEnd('\') -ne $where) { return "DRAUPNE Link is installed in $($e.Location), not $where" }
    if ($e.Key -notlike 'HKEY_LOCAL_MACHINE\*') { return 'DRAUPNE Link is installed for one user only (an installer before 0.5.3)' }
    return $null
}

<# Why the program folder is not that version's, or $null: its .cfg starts draupne-link-<version>.jar, which is
   there and the only one, and the jar's manifest says that version. #>
function Test-ProgramFolder([string]$Folder, [string]$Version) {
    $app = Join-Path $Folder 'app'
    $cfg = Join-Path $app "$script:AppName.cfg"
    $jar = "draupne-link-$Version.jar"
    if (-not (Test-Path -LiteralPath (Join-Path $Folder $script:AppExe))) { return "no $script:AppExe in $Folder" }
    if (-not (Test-Path -LiteralPath $cfg)) { return "no $cfg" }
    if ([IO.File]::ReadAllText($cfg).IndexOf('app.classpath=$APPDIR\' + $jar) -lt 0) { return "$cfg does not name $jar" }
    if (-not (Test-Path -LiteralPath (Join-Path $app $jar))) { return "no $jar in $app" }
    $others = @(Get-ChildItem -LiteralPath $app -Filter 'draupne-link-*.jar' -File | Where-Object { $_.Name -ne $jar } | ForEach-Object { $_.Name })
    if ($others.Count) { return "$app also holds " + ($others -join ', ') }
    $said = Get-JarVersion (Join-Path $app $jar)
    if ($said -and $said -ne $Version) { return "$jar says it is $said" }
    return $null
}

function Get-JarVersion([string]$Jar) {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
        try {
            $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
            if (-not $entry) { return $null }
            $r = New-Object IO.StreamReader($entry.Open())
            try { $text = $r.ReadToEnd() } finally { $r.Dispose() }
            $m = [regex]::Match($text, '(?m)^Implementation-Version:\s*(\S+)')
            if ($m.Success) { return $m.Groups[1].Value }
            return $null
        } finally {
            $zip.Dispose()
        }
    } catch {
        return $null
    }
}

<# The installer to run: its version from its name (DRAUPNE-Link-Setup-<version>.exe), size, SHA-256, signature. #>
function Get-InstallerInfo([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "no installer at $Path" }
    $f = Get-Item -LiteralPath $Path
    $m = [regex]::Match($f.Name, '^DRAUPNE-Link-Setup-(\d+\.\d+\.\d+)\.exe$')
    if (-not $m.Success) { throw "$($f.Name) is not named as DRAUPNE Link's installers are (DRAUPNE-Link-Setup-<version>.exe)" }
    $sig = Get-AuthenticodeSignature -LiteralPath $f.FullName
    $signer = ''; if ($sig.SignerCertificate) { $signer = [string]$sig.SignerCertificate.Subject }
    [pscustomobject]@{ Path = $f.FullName; Name = $f.Name; Version = $m.Groups[1].Value; Bytes = $f.Length
        Sha256 = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); Signature = [string]$sig.Status; Signer = $signer }
}

<# UPDATE's rule (SelfUpdate.signatureVerdict, releases not code-signed yet): a valid signature or none. #>
function Test-Signature($Info) {
    if ($Info.Signature -eq 'Valid' -or $Info.Signature -eq 'NotSigned') { return $null }
    return "the installer's signature does not check out ($($Info.Signature))"
}

# ---------------------------------------------------------------------------------------------------- the PC's state

function ConvertTo-Prefix([string]$Address) {
    $m = [regex]::Match($Address.Trim(), '^(\d+\.\d+\.\d+\.\d+)/(\d+\.\d+\.\d+\.\d+)$')
    if (-not $m.Success) { return $Address.Trim() }
    $bits = 0
    foreach ($o in $m.Groups[2].Value.Split('.')) { foreach ($c in [Convert]::ToString([int]$o, 2).ToCharArray()) { if ($c -eq [char]'1') { $bits++ } } }
    return $m.Groups[1].Value + '/' + $bits
}

function Get-FirewallRules([string]$Name) {
    $out = [System.Collections.Generic.List[object]]::new()
    foreach ($r in @(Get-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue)) {
        $addr = $r | Get-NetFirewallAddressFilter
        $port = $r | Get-NetFirewallPortFilter
        $out.Add([pscustomobject]@{ Name = $Name; Enabled = [string]$r.Enabled; Direction = [string]$r.Direction; Action = [string]$r.Action
                Profile = [string]$r.Profile; Protocol = [string]$port.Protocol; LocalPort = (@($port.LocalPort) -join ',')
                Remote = (@($addr.RemoteAddress | ForEach-Object { ConvertTo-Prefix $_ }) -join ',') })
    }
    return , $out
}

function Get-ShortcutTarget([string]$Lnk) {
    if (-not (Test-Path -LiteralPath $Lnk)) { return $null }
    try { return [string](New-Object -ComObject WScript.Shell).CreateShortcut($Lnk).TargetPath } catch { return '?' }
}

function Get-Rights([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $item = Get-Item -LiteralPath $Path -Force
    $acl = $item.GetAccessControl('Access, Owner')
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]) | ForEach-Object {
            [pscustomobject]@{ Sid = $_.IdentityReference.Value; Rights = [string]$_.FileSystemRights; Type = [string]$_.AccessControlType; Inherited = [bool]$_.IsInherited } })
    [pscustomobject]@{ Path = $Path; Link = (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0); Protected = [bool]$acl.AreAccessRulesProtected
        Owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value; Rules = $rules }
}

function Get-AppProcesses {
    @(Get-CimInstance Win32_Process -Filter "Name = '$script:AppExe' OR Name = '$script:OldExe'" -ErrorAction SilentlyContinue | ForEach-Object {
            $role = 'window'; $cmd = [string]$_.CommandLine
            if ($cmd -match '"?\s+(fleet|--fleet)\s*$') { $role = 'virtual fleet' } elseif ($cmd -match '\sstanag-encoder(\s|$)') { $role = 'STANAG encoder' }
            elseif ($cmd -match '\s(prepare-pc|cleanup-pc|install-tailscale)(\s|$)') { $role = 'installer task' }
            [pscustomobject]@{ Id = [int]$_.ProcessId; Name = [string]$_.Name; Path = [string]$_.ExecutablePath; Role = $role } })
}

function Get-PcState($F) {
    $log = Join-Path $F.ProgramData 'DraupneLink\setup.log'
    $power = ''
    try { $power = (& (Join-Path $env:SystemRoot 'System32\powercfg.exe') /getactivescheme | Out-String).Trim() } catch { }
    $guid = [regex]::Match($power, '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}').Value
    $task = $null
    try { $t = Get-ScheduledTask -TaskName $script:TailscaleTask -ErrorAction SilentlyContinue; if ($t) { $task = [string]$t.State } } catch { }
    $lines = @()
    if (Test-Path -LiteralPath $log) { $lines = @([IO.File]::ReadAllLines($log)) }
    $startMenu = Join-Path $F.ProgramData "Microsoft\Windows\Start Menu\Programs\$script:AppName\$script:AppName.lnk"
    [pscustomobject]@{
        Time         = (Get-Date).ToString('s')
        Installed    = @(Get-UninstallEntries)
        Program      = (Join-Path $F.ProgramFiles $script:AppName)
        ProgramJars  = @(Get-ChildItem -LiteralPath (Join-Path $F.ProgramFiles "$script:AppName\app") -Filter 'draupne-link-*.jar' -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
        LinkRule     = @(Get-FirewallRules $script:LinkRule)
        ApkRule      = @(Get-FirewallRules $script:ApkRule)
        OldRules     = @($script:OldRules | ForEach-Object { Get-FirewallRules $_ } | Where-Object { $_ })
        StartMenu    = [pscustomobject]@{ Path = $startMenu; Target = (Get-ShortcutTarget $startMenu) }
        DesktopAll   = [pscustomobject]@{ Path = (Join-Path $F.CommonDesktop "$script:AppName.lnk"); Target = (Get-ShortcutTarget (Join-Path $F.CommonDesktop "$script:AppName.lnk")) }
        DesktopOwn   = [pscustomobject]@{ Path = (Join-Path $F.Desktop "$script:AppName.lnk"); Target = (Get-ShortcutTarget (Join-Path $F.Desktop "$script:AppName.lnk")) }
        LogFolder    = (Get-Rights (Join-Path $F.ProgramData 'DraupneLink'))
        LogFile      = (Get-Rights $log)
        SetupLog     = $lines
        PowerPlan    = $guid
        TailscaleTask = $task
        Tailscale    = (Test-Path -LiteralPath (Join-Path $F.ProgramFiles 'Tailscale\tailscale.exe'))
        Processes    = @(Get-AppProcesses)
    }
}

function Save-State($State, [string]$Name) {
    if (-not $script:Run) { return }
    $State | ConvertTo-Json -Depth 6 | Out-File -LiteralPath (Join-Path $script:Run "state-$Name.json") -Encoding utf8
}

# ---------------------------------------------------------------------------------------------------- the checks

<# Both rules there once each, inbound, allowed, from this PC's networks and Tailscale only (WindowsSetup.RULE_REMOTE),
   on the port they had before; DroidDrone PC's old rules gone. #>
function Test-FirewallRules($Before, $After) {
    $want = (@($script:RuleRemote) | Sort-Object) -join ','
    foreach ($pair in @(@($script:LinkRule, 'LinkRule'), @($script:ApkRule, 'ApkRule'))) {
        $name = $pair[0]
        $now = @($After.($pair[1])); $was = @($Before.($pair[1]))
        if ($now.Count -ne 1) { Add-Check "firewall $name" 'FAIL' "$($now.Count) rules of that name"; continue }
        $r = $now[0]
        $remote = (@($r.Remote.Split(',')) | Sort-Object) -join ','
        $wasRemote = '(none)'; if ($was.Count) { $wasRemote = $was[0].Remote }
        $problems = @()
        if ($r.Enabled -ne 'True') { $problems += "enabled: $($r.Enabled)" }
        if ($r.Direction -ne 'Inbound') { $problems += "direction: $($r.Direction)" }
        if ($r.Action -ne 'Allow') { $problems += "action: $($r.Action)" }
        if ($remote -ne $want) { $problems += "from $($r.Remote), not $($script:RuleRemote -join ',')" }
        if ($was.Count -eq 1 -and $was[0].LocalPort -ne $r.LocalPort) { $problems += "port $($r.LocalPort), was $($was[0].LocalPort)" }
        $detail = "$($r.Protocol) $($r.LocalPort) from $($r.Remote) (before: from $wasRemote)"
        if ($name -eq $script:LinkRule -and $wasRemote -ne $r.Remote -and -not $problems) { $detail += ' - narrowed' }
        if ($problems) { Add-Check "firewall $name" 'FAIL' (($problems -join '; ') + "; $detail") } else { Add-Check "firewall $name" 'PASS' $detail }
    }
    if (@($After.OldRules).Count) { Add-Check 'firewall old rules' 'FAIL' "DroidDrone PC's rules still there: $((@($After.OldRules) | ForEach-Object { $_.Name }) -join ', ')" }
}

<# PcPrepLogTest's rule: ProgramData\DraupneLink is no link, owned by SYSTEM or Administrators, protected (nothing
   inherited from ProgramData, whose rights let every user add files), full control for SYSTEM and Administrators,
   reading for Users, nobody else; and setup.log in it is writable by none but those two. #>
function Test-LogRights($Folder, $File) {
    if (-not $Folder) { Add-Check 'ProgramData rights' 'FAIL' 'no ProgramData\DraupneLink'; return }
    $have = @($Folder.Rules | ForEach-Object { "$($_.Sid)=$($_.Rights)" + $(if ($_.Type -ne 'Allow') { " ($($_.Type))" } else { '' }) + $(if ($_.Inherited) { ' (inherited)' } else { '' }) } | Sort-Object)
    $want = @("$script:SidAdmins=FullControl", "$script:SidSystem=FullControl", "$script:SidUsers=ReadAndExecute, Synchronize") | Sort-Object
    $problems = @()
    if ($Folder.Link) { $problems += 'it is a link' }
    if (@($script:SidSystem, $script:SidAdmins) -notcontains $Folder.Owner) { $problems += "owner $($Folder.Owner)" }
    if (-not $Folder.Protected) { $problems += "it takes ProgramData's rights" }
    if (($have -join ';') -ne ($want -join ';')) { $problems += 'rules: ' + ($have -join '; ') }
    if ($File) {
        $writers = @($File.Rules | Where-Object { $_.Type -eq 'Allow' -and @($script:SidSystem, $script:SidAdmins) -notcontains $_.Sid -and $_.Rights -match 'FullControl|Modify|Write|Append|CreateFiles|Delete|ChangePermissions|TakeOwnership|268435456|1073741824' } | ForEach-Object { "$($_.Sid)=$($_.Rights)" })
        if ($writers) { $problems += 'setup.log writable by ' + ($writers -join ', ') }
    }
    $detail = "owner $($Folder.Owner), protected $($Folder.Protected): " + ($have -join '; ')
    if ($problems) { Add-Check 'ProgramData rights' 'FAIL' (($problems -join '; ') + " | $detail") } else { Add-Check 'ProgramData rights' 'PASS' $detail }
}

<# The installer's log kept as it was and a line of this run added: "Preparing this PC" (install) then "Firewall rules:
   done", or "Uninstalling: firewall rules removed". Returns why not, or $null. #>
function Test-SetupLog([string[]]$Before, [string[]]$After, [datetime]$Since, [string]$Want) {
    $Before = @($Before); $After = @($After)
    for ($i = 0; $i -lt $Before.Count; $i++) {
        if ($i -ge $After.Count -or $After[$i] -ne $Before[$i]) { return "line $($i + 1) is not as it was" }
    }
    $new = @($After | Select-Object -Skip $Before.Count)
    $at = -1
    for ($i = 0; $i -lt $new.Count; $i++) {
        $m = [regex]::Match($new[$i], '^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)\s+(.*)$')
        if (-not $m.Success -or -not $m.Groups[2].Value.StartsWith($Want)) { continue }
        $t = [datetime]::ParseExact($m.Groups[1].Value, 'yyyy-MM-ddTHH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
        if ($t -ge $Since.AddMinutes(-1)) { $at = $i }
    }
    if ($at -lt 0) { return "no new line `"$Want`" ($($new.Count) new lines)" }
    if ($Want -eq 'Preparing this PC' -and -not @($new | Select-Object -Skip $at | Where-Object { $_ -match 'Firewall rules: done' })) {
        return 'the firewall rules were not done: ' + ((@($new | Select-Object -Skip $at) | Where-Object { $_ -match 'Firewall' }) -join ' | ')
    }
    return $null
}

function Test-StartMenu($State, $F) {
    $exe = Get-InstalledExe $F
    if (-not $State.StartMenu.Target) { Add-Check 'Start menu' 'FAIL' "no $($State.StartMenu.Path)"; return }
    if ($State.StartMenu.Target -ne $exe) { Add-Check 'Start menu' 'FAIL' "$($State.StartMenu.Path) leads to $($State.StartMenu.Target), not $exe"; return }
    Add-Check 'Start menu' 'PASS' "$($State.StartMenu.Path) -> $exe"
}

function Test-DesktopShortcut($State, $F) {
    $exe = Get-InstalledExe $F
    $there = @(@($State.DesktopAll, $State.DesktopOwn) | Where-Object { $_.Target })
    if (-not $there) { Add-Check 'desktop shortcut' 'NOTE' "none (UPDATE's helper makes one on this user's desktop, the app at its start)"; return }
    $bad = @($there | Where-Object { $_.Target -ne $exe })
    $text = ($there | ForEach-Object { "$($_.Path) -> $($_.Target)" }) -join '; '
    if ($bad) { Add-Check 'desktop shortcut' 'FAIL' $text } else { Add-Check 'desktop shortcut' 'PASS' $text }
}

# ---------------------------------------------------------------------------------------------------- steps

function Test-Elevated {
    return ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

<# A test runs the script sealed: it stops before the first step that would change anything. #>
function Assert-NotSealed([string]$What) {
    if ($env:DRAUPNE_INSTALLER_TEST_SEALED) { throw "REFUSED: sealed (a test run) - $What not done" }
}

function New-RunFolder([string]$RootFolder, [string]$Mode) {
    $dir = Join-Path $RootFolder ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Mode)
    New-Item -ItemType Directory -Path $dir | Out-Null
    return $dir
}

function Get-ByteSum($Rows) {
    [long]$sum = 0
    foreach ($r in $Rows) { $sum += $r.Bytes }
    return $sum
}

<# Each place's files and bytes, said; returns all the bytes. #>
function Measure-Places($Places) {
    $total = [long]0
    foreach ($p in $Places) {
        $rows = Get-FileList $p -NoHash
        $bytes = Get-ByteSum $rows
        $total += $bytes
        $there = Test-Path -LiteralPath $p.Path
        Say ("  {0,-13} {1,-9} {2,7} files {3,9:N1} MB  {4}{5}" -f $p.Group, $p.Kind, $rows.Count, ($bytes / 1MB), $p.Path, $(if ($there) { '' } else { '  (not there)' }))
    }
    return $total
}

<# The copy aside needs room: what the places hold, a tenth more and 1 GB besides, free where the run folder goes. #>
function Assert-Room($Places, [string]$RootFolder) {
    Say 'What is copied aside:'
    $bytes = Measure-Places $Places
    if (-not $bytes) { $bytes = 0 }
    $drive = [IO.Path]::GetPathRoot($RootFolder)
    $free = (New-Object IO.DriveInfo $drive).AvailableFreeSpace
    if ($free -lt ($bytes * 1.1 + 1GB)) { throw ("REFUSED: the copy aside needs {0:N1} GB and {1} has {2:N1} GB free. Nothing installed." -f ($bytes / 1GB), $drive, ($free / 1GB)) }
}

function Invoke-Robocopy([string]$Source, [string]$Dest, [string]$Only, [string]$Log) {
    $robocopy = Join-Path $env:SystemRoot 'System32\Robocopy.exe'
    # copies (never /MOV, /MIR or /PURGE: nothing is moved or deleted anywhere), times and attributes kept, links not followed
    $a = @($Source.TrimEnd('\'), $Dest.TrimEnd('\'))
    if ($Only) { $a += $Only } else { $a += '/E' }
    $a += @('/COPY:DAT', '/DCOPY:T', '/XJ', '/R:2', '/W:1', '/NP', '/NFL', '/NDL', "/UNILOG+:$Log")
    & $robocopy @a | Out-Null
    return $LASTEXITCODE
}

<#
(b) The copy aside: every place copied into <run>\copy\<group> (robocopy: the places only read, nothing ever moved or
deleted, in the copy neither). -WhileRunning: the app still runs, so a file it holds may not copy now - it is copied
once the app is closed, when this is called again (robocopy then copies what changed).
#>
function Copy-Aside($Places, [string]$Run, [switch]$WhileRunning) {
    $copy = Join-Path $Run 'copy'
    foreach ($p in $Places) {
        if (-not (Test-Path -LiteralPath $p.Path -PathType Container)) { continue }
        $code = Invoke-Robocopy $p.Path (Join-Path $copy $p.Group) $p.Only (Join-Path $Run 'robocopy.log')
        if ($code -ge 8) {
            if ($WhileRunning) { Say "Some files of $($p.Path) did not copy while DRAUPNE Link runs (robocopy $code): copied again once it is closed." }
            else { throw "REFUSED: the copy of $($p.Path) failed (robocopy $code, robocopy.log). Nothing installed." }
        }
    }
}

<#
The places hashed where they are (the hash list hashes-<name>.tsv: the state the checks start from) and in the copy;
a file whose copy differs is copied once more, and the run stops when it still differs (something still writes).
Files in the copy alone are an earlier pass's (the app removed them while closing): kept, said. Returns the list.
#>
function Confirm-Copy($Places, [string]$Run, [string]$Name) {
    Say 'Hashing the files where they are...'
    $live = Get-FileLists $Places
    Write-HashList $live (Join-Path $Run "hashes-$Name.tsv")
    Say 'Hashing the copy...'
    $copyRoot = Join-Path $Run 'copy'
    $copies = foreach ($p in $Places) { New-Place $p.Group $p.Kind (Join-Path $copyRoot $p.Group) $p.What $p.Only }
    $copied = Get-FileLists @($copies)
    $diff = @(Compare-HashList $live $copied @())
    $byGroup = @{}; foreach ($p in $Places) { $byGroup[$p.Group] = $p }
    $index = @{}; foreach ($r in $live) { $index[$r.Group + '|' + $r.Path] = $r }
    $sha = [Security.Cryptography.SHA256]::Create()
    $still = [System.Collections.Generic.List[object]]::new()
    try {
        foreach ($d in @($diff | Where-Object { $_.Change -ne 'new' })) {
            $was = $index[$d.Group + '|' + $d.Path]
            if ($was.Sha256 -eq 'LINK') { continue }  # a link is listed, never copied or followed
            $src = Join-Path $byGroup[$d.Group].Path $d.Path
            $dst = Join-Path (Join-Path $copyRoot $d.Group) $d.Path
            if (Test-Path -LiteralPath $src -PathType Leaf) {
                New-Item -ItemType Directory -Path (Split-Path $dst -Parent) -Force | Out-Null
                Copy-Item -LiteralPath $src -Destination $dst -Force
            }
            if ((Get-Sha256 $dst $sha) -ne $was.Sha256 -or (Get-Sha256 $src $sha) -ne $was.Sha256) { $still.Add($d) }
        }
    } finally {
        $sha.Dispose()
    }
    $onlyInCopy = @($diff | Where-Object { $_.Change -eq 'new' })
    if ($diff.Count) { Write-Changes $diff (Join-Path $Run "changes-$Name-copy.tsv") }
    $bytes = Get-ByteSum $live
    $links = @($live | Where-Object { $_.Sha256 -eq 'LINK' })
    if ($still.Count) {
        $list = ($still | Select-Object -First 5 | ForEach-Object { "$($_.Group)\$($_.Path)" }) -join '; '
        throw "REFUSED: $($still.Count) files still change after the app closed ($list) - something writes there. Nothing installed."
    }
    $more = ''
    if ($onlyInCopy.Count) { $more += ", $($onlyInCopy.Count) more in the copy from its first pass" }
    if ($links.Count) { $more += ", $($links.Count) links listed, not followed" }
    Add-Check 'copy aside' 'PASS' ("{0} files, {1:N1} MB, copied into {2} and identical there; hash list hashes-{3}.tsv{4}" -f ($live.Count - $links.Count), ($bytes / 1MB), $copyRoot, $Name, $more)
    return , $live
}

function Get-LinkLogAge([string]$LogsDir) {
    if (-not (Test-Path -LiteralPath $LogsDir)) { return $null }
    $newest = Get-ChildItem -LiteralPath $LogsDir -Filter 'link-*.csv' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $newest) { return $null }
    return [pscustomobject]@{ Name = $newest.Name; Seconds = ([datetime]::UtcNow - $newest.LastWriteTimeUtc).TotalSeconds }
}

<#
(c) Closes DRAUPNE Link if it runs, as its own window's close does (its settings and logs written), and says so. Never
by force; never while its link log is still being written (10 rows a second while a link is open): a drone may hang
on that link. Returns whether it was running.
#>
function Close-App([string]$LogsDir) {
    $procs = @(Get-AppProcesses)
    if (-not $procs.Count) { Say 'DRAUPNE Link is not running: nothing to close.'; return $false }
    Say ('DRAUPNE Link is running: ' + (($procs | ForEach-Object { "pid $($_.Id) ($($_.Role))" }) -join ', '))
    $link = Get-LinkLogAge $LogsDir
    if ($link -and $link.Seconds -lt 20) {
        throw "REFUSED: DRAUPNE Link's link log $($link.Name) was written $([int]$link.Seconds) s ago - a link may be open. DISCONNECT in DRAUPNE Link first: it was not closed."
    }
    for ($round = 1; $round -le 3; $round++) {
        foreach ($p in @(Get-AppProcesses)) {
            $proc = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
            if ($proc -and $proc.MainWindowHandle -ne [IntPtr]::Zero) {
                Say "Closing DRAUPNE Link's window (pid $($p.Id), $($p.Role)), as its close button does."
                [void]$proc.CloseMainWindow()
            }
        }
        $until = (Get-Date).AddSeconds(20)
        while ((Get-Date) -lt $until -and @(Get-AppProcesses).Count) { Start-Sleep -Milliseconds 500 }
        if (-not @(Get-AppProcesses).Count) { Say 'DRAUPNE Link closed.'; return $true }
    }
    Say 'DRAUPNE Link is still running (a question on its screen?). Close it by hand now - it is never ended by force. Waiting up to 5 minutes.'
    $until = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $until -and @(Get-AppProcesses).Count) { Start-Sleep -Seconds 2 }
    if (@(Get-AppProcesses).Count) { throw 'REFUSED: DRAUPNE Link is still running: it was not closed.' }
    Say 'DRAUPNE Link closed.'
    return $true
}

<# msiexec's log as an argument: one without a space (jpackage's installer hands its arguments on to msiexec). #>
function Get-LogArgument([string]$Path) {
    if ($Path -notmatch '\s') { return $Path }
    try {
        $short = Join-Path (New-Object -ComObject Scripting.FileSystemObject).GetFolder((Split-Path $Path -Parent)).ShortPath (Split-Path $Path -Leaf)
        if ($short -notmatch '\s') { return $short }
    } catch { }
    return $null
}

function Invoke-Setup([string]$Exe, [string[]]$Arguments, [string]$Log) {
    $all = @($Arguments)
    $logArg = Get-LogArgument $Log
    if ($logArg) { $all += @('/l*v', $logArg) } else { Say "No installer log: its folder's path has a space ($Log)." }
    Say ("Running: `"$Exe`" " + ($all -join ' ') + '  (Windows asks once for administrator rights)')
    $p = Start-Process -FilePath $Exe -ArgumentList $all -PassThru
    $null = $p.Handle  # the exit code is kept only when the handle was taken while it ran
    $p.WaitForExit()
    return $p.ExitCode
}

function Get-ExitWords([int]$Code) {
    switch ($Code) {
        0 { 'done' } 3010 { 'done, Windows wants a restart' } 1602 { 'cancelled' } 1223 { 'the Windows prompt was declined' }
        1603 { 'failed (see the log: "Return value 3")' } 1618 { 'another install was running' } 1638 { 'another version of this product is installed' }
        default { 'see the log' }
    }
}

function Get-MsiLogLines([string]$Log) {
    if (-not $Log -or -not (Test-Path -LiteralPath $Log)) { return @() }
    @(Get-Content -LiteralPath $Log | Where-Object { $_ -match 'Product: .*--|Windows Installer (installed|removed|reconfigured) the product|MainEngineThread is returning|Return value 3|CheckAppRunning|Doing action: (PreparePc|CleanupPc|RemoveExistingProducts)' } | Select-Object -First 30)
}

<# (f) The installed app started once as UPDATE's helper starts it, its own log read, its window closed again. #>
function Start-AppOnce($F, [string]$Version, [int]$Seconds) {
    $exe = Get-InstalledExe $F
    $logs = Join-Path $F.AppData 'DraupneLink\logs'
    $since = Get-Date
    $today = Join-Path $logs ('app-' + $since.ToString('yyyy-MM-dd') + '.log')
    $from = 0; if (Test-Path -LiteralPath $today) { $from = (Get-Item -LiteralPath $today).Length }
    Say "Starting $exe"
    if (Test-Elevated) { Start-Process -FilePath (Join-Path $env:SystemRoot 'explorer.exe') -ArgumentList ('"' + $exe + '"') }  # as this user, not as administrator
    else { Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe -Parent) }
    $started = $null; $settings = $null; $lines = @()
    $until = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $until -and -not ($started -and $settings)) {
        Start-Sleep -Seconds 1
        $lines = @(Read-NewLines $today $from)
        $started = $lines | Where-Object { $_ -match "=== DRAUPNE Link (\S+) started" } | Select-Object -First 1
        $settings = $lines | Where-Object { $_ -match 'DRAUPNE Link \S+\. Phone app: .*Settings: ' } | Select-Object -First 1
    }
    if ($started -and $started -match "=== DRAUPNE Link $([regex]::Escape($Version)) started") { Add-Check 'app started' 'PASS' $started.Trim() }
    elseif ($started) { Add-Check 'app started' 'FAIL' "not $Version - $($started.Trim())" }
    else { Add-Check 'app started' 'FAIL' "no `"=== DRAUPNE Link $Version started`" in $today within 90 s" }
    if ($settings) { Add-Check 'settings read' 'PASS' ($settings.Trim() -replace '^\S+\s+\S+\s+', '') } else { Add-Check 'settings read' 'FAIL' 'no "Settings: ..." line in its log' }
    Start-Sleep -Seconds ([Math]::Max(0, $Seconds))
    $proc = @(Get-AppProcesses | Where-Object { $_.Role -eq 'window' }) | Select-Object -First 1
    if ($proc) {
        $title = (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue).MainWindowTitle
        if ($title -eq "DRAUPNE Link $Version") { Add-Check 'window title' 'PASS' $title } else { Add-Check 'window title' 'NOTE' "`"$title`"" }
    } else { Add-Check 'app running' 'FAIL' "DRAUPNE Link is not running $Seconds s after its start" }
    $lines = @(Read-NewLines $today $from)
    if ($script:Run) { [IO.File]::WriteAllLines((Join-Path $script:Run 'app-log-new-lines.txt'), [string[]]$lines, (New-Object Text.UTF8Encoding($false))) }
    $bad = @($lines | Where-Object { $_ -match 'UNCAUGHT|Exception|WINDOW NOT RESPONDING|Could not save settings|NOT READ|unreadable|had bad lines|Settings updated to|Last update: (?!OK)' })
    if ($bad) { Add-Check 'app log' 'NOTE' ("$($bad.Count) lines to look at (app-log-new-lines.txt): " + (($bad | Select-Object -First 3) -join ' | ')) }
    else { Add-Check 'app log' 'PASS' "$($lines.Count) new lines, no error among them" }
    try {
        [void](Close-App $logs)
        Add-Check 'app closed' 'PASS' 'by its window, as its close button closes it'
    } catch {
        Add-Check 'app closed' 'FAIL' $_.Exception.Message
        $script:Stopped = $true
    }
}

function Read-NewLines([string]$File, [long]$From) {
    if (-not (Test-Path -LiteralPath $File)) { return @() }
    $s = New-Object IO.FileStream($File, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        if ($s.Length -lt $From) { $From = 0 }
        [void]$s.Seek($From, [IO.SeekOrigin]::Begin)
        $r = New-Object IO.StreamReader($s, [Text.Encoding]::UTF8)
        $text = $r.ReadToEnd()
    } finally {
        $s.Dispose()
    }
    return @($text -split "\r?\n" | Where-Object { $_ })
}

<# The released installer -Back puts back: a copy given (-BackInstaller), else downloaded from the project's GitHub
   release into the run folder - both checked against the size and SHA-256 above and the download also against
   GitHub's own digest. #>
function Get-ReleasedInstaller([string]$Version, [string]$Local, [string]$Run) {
    $want = $script:Released[$Version]
    if (-not $want) { throw "REFUSED: no released installer known for $Version" }
    if ($Local) {
        $file = (Get-Item -LiteralPath $Local).FullName
    } else {
        Assert-NotSealed 'download'
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Say "Asking GitHub for release v$Version's files..."
        $release = Invoke-RestMethod -Uri ($script:ReleaseApi + 'v' + $Version) -Headers @{ Accept = 'application/vnd.github+json' } -UseBasicParsing
        $asset = @($release.assets | Where-Object { $_.name -eq $want.Name }) | Select-Object -First 1
        if (-not $asset) { throw "REFUSED: release v$Version has no $($want.Name)" }
        $url = [string]$asset.browser_download_url
        if (-not $url.StartsWith($script:Downloads + "v$Version/")) { throw "REFUSED: $($want.Name) is not under the project's own release downloads: $url" }
        if ([string](Get-Prop $asset 'digest') -ne ('sha256:' + $want.Sha256)) { throw "REFUSED: GitHub lists $($want.Name) with $((Get-Prop $asset 'digest')), not sha256:$($want.Sha256)" }
        $dir = Join-Path $Run 'download'
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        $file = Join-Path $dir $want.Name
        Say "Downloading $url"
        Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
    }
    $info = Get-InstallerInfo $file
    if ($info.Name -ne $want.Name -or $info.Bytes -ne $want.Bytes -or $info.Sha256 -ne $want.Sha256) {
        throw "REFUSED: $file is not the released $($want.Name) ($($info.Bytes) bytes, SHA-256 $($info.Sha256); released: $($want.Bytes) bytes, $($want.Sha256))"
    }
    $why = Test-Signature $info
    if ($why) { throw "REFUSED: $why" }
    Add-Check 'released installer' 'PASS' "$file, SHA-256 $($info.Sha256) as released, signature $($info.Signature)"
    return $info
}

function Write-Summary {
    if (-not $script:Run) { return }
    $lines = @($script:Checks | ForEach-Object { "{0,-5} {1}: {2}" -f $_.Verdict, $_.Check, $_.Detail })
    [IO.File]::WriteAllLines((Join-Path $script:Run 'summary.txt'), [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
}

function Show-BackHelp([string]$Run) {
    $self = $script:Self
    $r = $script:Released[$script:BackTo]
    Say "To go BACK to $script:BackTo (its released installer, then the settings copied back from this run's copy):"
    Write-Host "    powershell -NoProfile -ExecutionPolicy Bypass -File `"$self`" -Back -From `"$Run`""
    Write-Host "    (no internet: add -BackInstaller <a copy of $($r.Name)>, SHA-256 $($r.Sha256))"
    Write-Host "  By hand instead: $($script:Downloads)v$script:BackTo/$($r.Name) - check its SHA-256, run it (it replaces the"
    Write-Host "  newer DRAUPNE Link: jpackage's installers take a downgrade), then copy $Run\copy\appdata\$script:SettingsFile"
    Write-Host "  back into %APPDATA%\DraupneLink (DRAUPNE Link closed); changes-overall.tsv says what else changed."
}

# ---------------------------------------------------------------------------------------------------- the modes

function Invoke-Check($F, [string]$RootFolder) {
    Say "Read only: nothing is copied, closed, installed or written."
    $entries = @(Get-UninstallEntries)
    foreach ($e in $entries) { Say "Installed: $($e.Name) $($e.Version), product code $($e.Code), in $($e.Location) ($($e.Key))" }
    $why = Test-Installed $entries $Expect $F.ProgramFiles
    if ($why) { Add-Check 'installed version' 'FAIL' "would refuse: $why" } else { Add-Check 'installed version' 'PASS' "DRAUPNE Link $Expect, product code $(Get-ProductCode $Expect)" }
    if (-not $why) {
        $p = Test-ProgramFolder (Join-Path $F.ProgramFiles $script:AppName) $Expect
        if ($p) { Add-Check 'program folder' 'FAIL' $p } else { Add-Check 'program folder' 'PASS' "draupne-link-$Expect.jar" }
    }
    if ($Installer) {
        $info = Get-InstallerInfo $Installer
        Say "Installer: $($info.Path), $($info.Version), $($info.Bytes) bytes, SHA-256 $($info.Sha256), signature $($info.Signature) $($info.Signer)"
        if ((Compare-Version $info.Version $Expect) -le 0) { Add-Check 'installer version' 'FAIL' "$($info.Version) is not newer than ${Expect}: no upgrade" }
        else { Add-Check 'installer version' 'PASS' "$($info.Version) over $Expect, product code $(Get-ProductCode $info.Version)" }
        $sig = Test-Signature $info; if ($sig) { Add-Check 'installer signature' 'FAIL' $sig }
    }
    $places = Get-Places $F
    Say 'What a run copies aside:'
    $bytes = Measure-Places $places
    $drive = [IO.Path]::GetPathRoot($RootFolder)
    $free = (New-Object IO.DriveInfo $drive).AvailableFreeSpace
    Say ("Copy aside into {0}: {1:N1} GB needed (twice for -Back after an upgrade), {2:N1} GB free on {3}" -f $RootFolder, ($bytes / 1GB), ($free / 1GB), $drive)
    $state = Get-PcState $F
    foreach ($p in @($state.Processes)) { Say "Running: pid $($p.Id) $($p.Name) ($($p.Role))" }
    $link = Get-LinkLogAge (Join-Path $F.AppData 'DraupneLink\logs')
    if ($link) { Say ("Newest link log: {0}, {1:N0} s old" -f $link.Name, $link.Seconds) }
    foreach ($r in @($state.LinkRule) + @($state.ApkRule) + @($state.OldRules)) { Say "Firewall: $($r.Name) $($r.Direction) $($r.Action) $($r.Protocol) $($r.LocalPort) from $($r.Remote), enabled $($r.Enabled)" }
    Say "Start menu: $($state.StartMenu.Path) -> $($state.StartMenu.Target)"
    Say "Desktop: $($state.DesktopAll.Path) -> $($state.DesktopAll.Target); $($state.DesktopOwn.Path) -> $($state.DesktopOwn.Target)"
    Test-LogRights $state.LogFolder $state.LogFile
    Say '      (installers before 1.0.72 made that folder with ProgramData''s own rights; 1.0.72''s brings it to the rule)'
    Say "setup.log: $(@($state.SetupLog).Count) lines; the last: $(@($state.SetupLog) | Select-Object -Last 1)"
    Say "Power plan: $($state.PowerPlan); Tailscale installed: $($state.Tailscale); Tailscale task: $($state.TailscaleTask)"
    if ($why) { $script:Refused = $true }
}

function Invoke-Upgrade($F, [string]$RootFolder) {
    # (a) what is installed, and what is to be installed
    $entries = @(Get-UninstallEntries)
    $why = Test-Installed $entries $Expect $F.ProgramFiles
    if (-not $why) { $why = Test-ProgramFolder (Join-Path $F.ProgramFiles $script:AppName) $Expect }
    if ($why) { throw "REFUSED: $why. Nothing was copied, closed or installed." }
    Add-Check 'installed version' 'PASS' "DRAUPNE Link $Expect, product code $(Get-ProductCode $Expect)"
    $info = Get-InstallerInfo $Installer
    if ((Compare-Version $info.Version $Expect) -le 0) { throw "REFUSED: $($info.Name) is not newer than $Expect" }
    $sig = Test-Signature $info; if ($sig) { throw "REFUSED: $sig" }
    Add-Check 'installer' 'PASS' "$($info.Name) ($($info.Version)), SHA-256 $($info.Sha256), signature $($info.Signature)"
    Assert-NotSealed 'the copy aside'

    # (b) the copy aside, (c) the app closed, the copy brought up to date and checked
    $script:Run = New-RunFolder $RootFolder 'upgrade'
    Start-Transcript -LiteralPath (Join-Path $script:Run 'transcript.txt') | Out-Null
    Say "Run folder: $script:Run"
    $installerFacts = $info | Format-List | Out-String
    [IO.File]::WriteAllText((Join-Path $script:Run 'installer.txt'), $installerFacts)
    $places = Get-Places $F
    $user = @($places | Where-Object { $_.Kind -eq 'user' }) | ForEach-Object { $_.Group }
    Assert-Room $places $RootFolder
    $before = Get-PcState $F
    Save-State $before 'before'
    Say 'Copying aside (copy, never move)...'
    Copy-Aside $places $script:Run -WhileRunning:(@($before.Processes).Count -gt 0)
    $wasRunning = Close-App (Join-Path $F.AppData 'DraupneLink\logs')
    if ($wasRunning) {
        Say 'Copying again what the app wrote while closing...'
        Copy-Aside $places $script:Run
        $before = Get-PcState $F
        Save-State $before 'before-closed'
    }
    $base = Confirm-Copy $places $script:Run 'before'

    # (d) the installer, as UPDATE's helper runs it
    $msiLog = Join-Path $script:Run 'msi-upgrade.log'
    $since = Get-Date
    $script:Installed = $true
    $code = Invoke-Setup $info.Path $script:UpdateArgs $msiLog
    $words = Get-ExitWords $code
    foreach ($l in (Get-MsiLogLines $msiLog)) { Say "  msi: $($l.Trim())" }
    if ($code -ne 0 -and $code -ne 3010) {
        Add-Check 'installer ran' 'FAIL' "exit code $code ($words), log $msiLog"
        $script:Stopped = $true
    } else {
        Add-Check 'installer ran' 'PASS' "exit code $code ($words), log $msiLog"
    }

    # (e) the checks
    $after = Get-PcState $F
    Save-State $after 'after-install'
    $now = @($after.Installed | Where-Object { $_.Name -eq $script:AppName })
    $newCode = Get-ProductCode $info.Version; $oldCode = Get-ProductCode $Expect
    if ($now.Count -eq 1 -and $now[0].Version -eq $info.Version -and $now[0].Code -eq $newCode) { Add-Check 'new version installed' 'PASS' "$($info.Version), product code $newCode, in $($now[0].Location)" }
    else { Add-Check 'new version installed' 'FAIL' ('installed: ' + (($now | ForEach-Object { "$($_.Version) $($_.Code)" }) -join '; ')) }
    $p = Test-ProgramFolder (Join-Path $F.ProgramFiles $script:AppName) $info.Version
    $oldLeft = @($after.Installed | Where-Object { $_.Code -eq $oldCode })
    if ($p -or $oldLeft.Count) { Add-Check 'old version replaced' 'FAIL' ((@($p) + @($oldLeft | ForEach-Object { "$Expect still listed ($($_.Code))" }) | Where-Object { $_ }) -join '; ') }
    else { Add-Check 'old version replaced' 'PASS' "no $Expect entry, the program folder holds draupne-link-$($info.Version).jar alone" }
    $afterFiles = Get-FileLists @($places | Where-Object { $_.Kind -eq 'user' })
    Write-HashList $afterFiles (Join-Path $script:Run 'hashes-after-install.tsv')
    $userBase = @($base | Where-Object { $user -contains $_.Group })
    Add-CompareCheck 'user files after the install' (Compare-HashList $userBase $afterFiles (Get-ExpectedChanges 'install' $userBase)) (Join-Path $script:Run 'changes-after-install.tsv')
    Test-FirewallRules $before $after
    Test-StartMenu $after $F
    Test-DesktopShortcut $after $F
    Test-LogRights $after.LogFolder $after.LogFile
    $s = Test-SetupLog $before.SetupLog $after.SetupLog $since 'Preparing this PC'
    if ($s) { Add-Check 'setup.log' 'FAIL' $s } else { Add-Check 'setup.log' 'PASS' "kept, and this install's lines added: $((@($after.SetupLog) | Select-Object -Skip @($before.SetupLog).Count) -join ' | ')" }
    if ($after.TailscaleTask) { Add-Check 'Tailscale task' 'NOTE' "$script:TailscaleTask is there ($($after.TailscaleTask))" }
    if ($after.PowerPlan -ne $before.PowerPlan) { Add-Check 'power plan' 'NOTE' "$($before.PowerPlan) -> $($after.PowerPlan) (the installer sets High performance)" }

    # (f) the new app started once
    if ($NoAppStart -or $script:Stopped) {
        Say 'The new app is not started (-NoAppStart, or the installer failed).'
    } else {
        Start-AppOnce $F $info.Version $AppSeconds
        if ($script:Stopped) {
            Say 'DRAUPNE Link still runs: its files are not compared again.'
        } else {
            $afterApp = Get-FileLists @($places | Where-Object { $_.Kind -eq 'user' })
            Write-HashList $afterApp (Join-Path $script:Run 'hashes-after-app.tsv')
            Add-CompareCheck 'user files after the app ran' (Compare-HashList $afterFiles $afterApp (Get-ExpectedChanges 'app' $afterFiles)) (Join-Path $script:Run 'changes-after-app.tsv')
            Add-CompareCheck 'user files, before the install to after the app' (Compare-HashList $userBase $afterApp (Get-ExpectedChanges 'app' $userBase)) (Join-Path $script:Run 'changes-overall.tsv')
            Add-SettingsCheck 'settings keys' (Join-Path $script:Run "copy\appdata\$script:SettingsFile") (Join-Path $F.AppData "DraupneLink\$script:SettingsFile")
            Save-State (Get-PcState $F) 'after-app'
        }
    }
    # (g)
    Show-BackHelp $script:Run
}

function Find-UpgradeRun([string]$RootFolder) {
    if (-not (Test-Path -LiteralPath $RootFolder)) { return $null }
    $runs = @(Get-ChildItem -LiteralPath $RootFolder -Directory | Where-Object { $_.Name -like '*-upgrade' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'hashes-before.tsv')) } | Sort-Object Name -Descending)
    if ($runs.Count) { return $runs[0].FullName }
    return $null
}

<# -Back's settings: from the run folder's copy, every file missing now copied back, and the settings files that differ
   copied back over the one there - that one first kept in <this run>\replaced. Other differences only listed. #>
function Restore-Settings($Places, [string]$FromRun, [string]$Run) {
    $list = Read-HashList (Join-Path $FromRun 'hashes-before.tsv')
    $byGroup = @{}; foreach ($p in $Places) { $byGroup[$p.Group] = $p }
    $user = @($Places | Where-Object { $_.Kind -eq 'user' } | ForEach-Object { $_.Group })
    $now = Get-FileLists @($Places | Where-Object { $_.Kind -eq 'user' })
    $diff = Compare-HashList @($list | Where-Object { $user -contains $_.Group }) $now @()
    $restored = 0; $replaced = 0; $left = [System.Collections.Generic.List[object]]::new()
    foreach ($d in $diff) {
        if ($d.Change -eq 'new') { continue }
        $src = Join-Path (Join-Path (Join-Path $FromRun 'copy') $d.Group) $d.Path
        $dst = Join-Path $byGroup[$d.Group].Path $d.Path
        if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { $left.Add($d); continue }
        $isSettings = @($script:SettingsFiles | Where-Object { $_.Group -eq $d.Group -and $_.Path -eq $d.Path }).Count -gt 0
        if ($d.Change -eq 'missing') {
            New-Item -ItemType Directory -Path (Split-Path $dst -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $src -Destination $dst
            $restored++
        } elseif ($isSettings) {
            $keep = Join-Path (Join-Path (Join-Path $Run 'replaced') $d.Group) $d.Path
            New-Item -ItemType Directory -Path (Split-Path $keep -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $dst -Destination $keep
            Copy-Item -LiteralPath $src -Destination $dst -Force
            Say "Copied back: $($d.Group)\$($d.Path) (the one there kept in $keep)"
            $replaced++
        } else {
            $left.Add($d)
        }
    }
    Add-Check 'settings copied back' 'PASS' "from $FromRun`: $replaced settings files copied back, $restored missing files copied back"
    if ($left.Count) {
        Write-Changes $left (Join-Path $Run 'changes-left.tsv')
        Add-Check 'other differences' 'NOTE' ("$($left.Count) files differ from the copy and were left as they are (changes-left.tsv): " + (($left | Select-Object -First 5 | ForEach-Object { "$($_.Change) $($_.Group)\$($_.Path)" }) -join '; '))
    }
}

function Invoke-Back($F, [string]$RootFolder) {
    $entries = @(Get-UninstallEntries)
    foreach ($e in $entries) { Say "Installed now: $($e.Name) $($e.Version) ($($e.Code))" }
    if (@($entries | Where-Object { $_.Name -ne $script:AppName }).Count) { throw 'REFUSED: DroidDrone PC is installed: not the PC this test is for' }
    $FromRun = $From
    if (-not $FromRun) { $FromRun = Find-UpgradeRun $RootFolder }
    if (-not $FromRun -or -not (Test-Path -LiteralPath (Join-Path $FromRun 'hashes-before.tsv'))) { throw "REFUSED: no upgrade run folder with hashes-before.tsv to copy the settings back from (-From <folder>)" }
    $already = $null -eq (Test-Installed $entries $script:BackTo $F.ProgramFiles)
    Assert-NotSealed 'the copy aside'

    $script:Run = New-RunFolder $RootFolder 'back'
    Start-Transcript -LiteralPath (Join-Path $script:Run 'transcript.txt') | Out-Null
    Say "Run folder: $script:Run; settings from $FromRun"
    $places = Get-Places $F
    Assert-Room $places $RootFolder
    $before = Get-PcState $F
    Save-State $before 'before'
    Copy-Aside $places $script:Run -WhileRunning:(@($before.Processes).Count -gt 0)
    $wasRunning = Close-App (Join-Path $F.AppData 'DraupneLink\logs')
    if ($wasRunning) { Copy-Aside $places $script:Run; $before = Get-PcState $F }
    [void](Confirm-Copy $places $script:Run 'before')

    if ($already) {
        Add-Check 'installed version' 'PASS' "$script:BackTo is installed already: only the settings are copied back"
    } else {
        $info = Get-ReleasedInstaller $script:BackTo $BackInstaller $script:Run
        $msiLog = Join-Path $script:Run 'msi-back.log'
        $since = Get-Date
        $script:Installed = $true
        $code = Invoke-Setup $info.Path $script:UpdateArgs $msiLog
        foreach ($l in (Get-MsiLogLines $msiLog)) { Say "  msi: $($l.Trim())" }
        if ($code -ne 0 -and $code -ne 3010) { Add-Check 'installer ran' 'FAIL' "exit code $code ($(Get-ExitWords $code)), log $msiLog"; $script:Stopped = $true }
        else { Add-Check 'installer ran' 'PASS' "exit code $code ($(Get-ExitWords $code)), log $msiLog" }
        $after = Get-PcState $F
        Save-State $after 'after-install'
        $why = Test-Installed $after.Installed $script:BackTo $F.ProgramFiles
        if (-not $why) { $why = Test-ProgramFolder (Join-Path $F.ProgramFiles $script:AppName) $script:BackTo }
        if ($why) { Add-Check "$script:BackTo installed" 'FAIL' $why } else { Add-Check "$script:BackTo installed" 'PASS' "product code $(Get-ProductCode $script:BackTo), draupne-link-$script:BackTo.jar" }
        Test-StartMenu $after $F
        $s = Test-SetupLog $before.SetupLog $after.SetupLog $since 'Preparing this PC'
        if ($s) { Add-Check 'setup.log' 'FAIL' $s } else { Add-Check 'setup.log' 'PASS' 'kept, and this install''s lines added' }
        foreach ($r in @($after.LinkRule) + @($after.ApkRule)) { Add-Check "firewall $($r.Name)" 'NOTE' "$($r.Protocol) $($r.LocalPort) from $($r.Remote) (as $script:BackTo makes it)" }
    }
    Restore-Settings $places $FromRun $script:Run
}

function Invoke-Uninstall($F, [string]$RootFolder) {
    $entries = @(Get-UninstallEntries | Where-Object { $_.Name -eq $script:AppName })
    if ($entries.Count -ne 1) { throw "REFUSED: DRAUPNE Link is installed $($entries.Count) times; one expected" }
    $e = $entries[0]
    if ($e.Code -ne (Get-ProductCode $e.Version)) { throw "REFUSED: DRAUPNE Link $($e.Version)'s product code $($e.Code) is not jpackage's for that version" }
    Say "To uninstall: DRAUPNE Link $($e.Version) ($($e.Code)). Windows' uninstall removes the program folder, the Start"
    Say 'menu entry, the installer''s desktop shortcut, the installed-apps entry, the two firewall rules and a leftover'
    Say 'Tailscale task; it keeps every setting, log, map, flight and recording, setup.log, Tailscale and the power plan.'
    Assert-NotSealed 'the copy aside'
    $script:Run = New-RunFolder $RootFolder 'uninstall'
    Start-Transcript -LiteralPath (Join-Path $script:Run 'transcript.txt') | Out-Null
    Say "Run folder: $script:Run"
    $places = Get-Places $F
    Assert-Room $places $RootFolder
    $before = Get-PcState $F
    Save-State $before 'before'
    Copy-Aside $places $script:Run -WhileRunning:(@($before.Processes).Count -gt 0)
    $wasRunning = Close-App (Join-Path $F.AppData 'DraupneLink\logs')
    if ($wasRunning) { Copy-Aside $places $script:Run; $before = Get-PcState $F }
    $base = Confirm-Copy $places $script:Run 'before'
    $msiLog = Join-Path $script:Run 'msi-uninstall.log'
    $since = Get-Date
    $script:Installed = $true
    $code = Invoke-Setup (Join-Path $env:SystemRoot 'System32\msiexec.exe') @('/x', $e.Code, '/passive', '/norestart') $msiLog
    foreach ($l in (Get-MsiLogLines $msiLog)) { Say "  msi: $($l.Trim())" }
    if ($code -ne 0 -and $code -ne 3010) { Add-Check 'uninstall ran' 'FAIL' "exit code $code ($(Get-ExitWords $code)), log $msiLog" }
    else { Add-Check 'uninstall ran' 'PASS' "exit code $code ($(Get-ExitWords $code)), log $msiLog" }
    $after = Get-PcState $F
    Save-State $after 'after-uninstall'
    if (@($after.Installed | Where-Object { $_.Name -eq $script:AppName }).Count) { Add-Check 'entry removed' 'FAIL' 'DRAUPNE Link is still listed' } else { Add-Check 'entry removed' 'PASS' 'not listed' }
    $prog = Join-Path $F.ProgramFiles $script:AppName
    if (Test-Path -LiteralPath $prog) { Add-Check 'program removed' 'FAIL' "$prog is still there: $(@(Get-ChildItem -LiteralPath $prog -Recurse -File -Force).Count) files" } else { Add-Check 'program removed' 'PASS' "$prog gone" }
    if ($after.StartMenu.Target) { Add-Check 'Start menu removed' 'FAIL' $after.StartMenu.Path } else { Add-Check 'Start menu removed' 'PASS' 'gone' }
    if ($after.DesktopAll.Target) { Add-Check 'desktop shortcut (all users)' 'FAIL' "$($after.DesktopAll.Path) is still there" }
    if ($after.DesktopOwn.Target) { Add-Check "this user's desktop shortcut" 'NOTE' "$($after.DesktopOwn.Path) stays (the app's own; it leads to $($after.DesktopOwn.Target))" }
    $rules = @($after.LinkRule) + @($after.ApkRule) + @($after.OldRules)
    if ($rules.Count) { Add-Check 'firewall rules removed' 'FAIL' (($rules | ForEach-Object { $_.Name }) -join ', ') } else { Add-Check 'firewall rules removed' 'PASS' "$script:LinkRule, $script:ApkRule gone" }
    if ($after.TailscaleTask) { Add-Check 'Tailscale task' 'FAIL' "$script:TailscaleTask is still there" }
    $s = Test-SetupLog $before.SetupLog $after.SetupLog $since 'Uninstalling: firewall rules removed'
    if ($s) { Add-Check 'setup.log' 'FAIL' $s } else { Add-Check 'setup.log' 'PASS' 'kept, the uninstall''s line added' }
    $user = @($places | Where-Object { $_.Kind -eq 'user' }) | ForEach-Object { $_.Group }
    $afterFiles = Get-FileLists @($places | Where-Object { $_.Kind -eq 'user' })
    Write-HashList $afterFiles (Join-Path $script:Run 'hashes-after-uninstall.tsv')
    $userBase = @($base | Where-Object { $user -contains $_.Group })
    Add-CompareCheck 'user files after the uninstall' (Compare-HashList $userBase $afterFiles (Get-ExpectedChanges 'uninstall' $userBase)) (Join-Path $script:Run 'changes-after-uninstall.tsv')
    Say "To install again: -Back -From <an upgrade run folder> (the released $script:BackTo), or -Installer <a setup.exe> with -Expect of what is then installed."
}

if ($LoadOnly) { return }

# ---------------------------------------------------------------------------------------------------- the run

$modes = @(@($Check.IsPresent, $Back.IsPresent, $Uninstall.IsPresent, ([bool]$Installer -and -not $Check)) | Where-Object { $_ })
if ($modes.Count -ne 1) {
    Write-Host 'One of: -Check [-Installer <setup.exe>], -Installer <setup.exe> [-Expect 1.0.69], -Back [-From <run folder>] [-BackInstaller <file>], -Uninstall. Get-Help -Full for the rest.'
    exit 2
}
$F = Get-PcFolders
if (-not $Root) { $Root = Join-Path $F.LocalAppData 'DraupneBench\installer-test' }
if (-not $Check -and (Test-Elevated) -and -not $AllowElevated) {
    Write-Host 'REFUSED: run from a normal PowerShell, not as administrator - the app is this user''s, and Windows asks once for the installer itself (as for UPDATE). -AllowElevated when this administrator IS the pilot''s own account.'
    exit 2
}
$exit = 0
try {
    if ($Check) { Invoke-Check $F $Root }
    elseif ($Back) { Invoke-Back $F $Root }
    elseif ($Uninstall) { Invoke-Uninstall $F $Root }
    else { Invoke-Upgrade $F $Root }
    if ($Check) {
        if ($script:Refused) { $exit = 2 }
    } else {
        if (@($script:Checks | Where-Object { $_.Verdict -eq 'FAIL' }).Count) { $exit = 1 }
        if ($script:Stopped) { $exit = 3 }
    }
} catch {
    $text = $_.Exception.Message
    Say $text
    # refused: nothing was installed; anything that stops the run once an installer ran is 3
    if ($text.StartsWith('REFUSED') -and -not $script:Installed) { $exit = 2 } else { $exit = 3 }
    if (-not $text.StartsWith('REFUSED')) { Say $_.ScriptStackTrace }
} finally {
    if ($script:Run) {
        $fails = @($script:Checks | Where-Object { $_.Verdict -eq 'FAIL' }).Count
        Say ("{0} checks, {1} failed. Everything of this run: {2}" -f $script:Checks.Count, $fails, $script:Run)
        Write-Summary
        try { Stop-Transcript | Out-Null } catch { }
    }
}
exit $exit
