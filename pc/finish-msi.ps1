# Finishes a jpackage-built MSI: gives it BMS Companion's artwork, and makes it replace whatever copy is already
# installed instead of refusing to run. desktop/build.gradle.kts runs this after packageMsi.
#
#   powershell -ExecutionPolicy Bypass -File pc\finish-msi.ps1 -Msi <file.msi> -Banner <banner.bmp> -Dialog <dialog.bmp>
#
# 1. Artwork. jpackage builds the installer with WiX's stock bitmaps and gives no way to change them: they are WiX
#    variables, jpackage's main.wxs defines neither, and the --resource-dir that could replace that file is emptied by
#    the Compose plugin inside its own packaging task. So the two bitmaps are swapped in the Binary table of the
#    finished installer, which is exactly where WiX keeps them.
#
# 2. Product code. jpackage derives it from the vendor, the name and the version alone
#    (UUIDv3 of "ProductCode/<vendor>/<name>/<version>"), so every build of one version carries the same code. Windows
#    Installer then refuses a package whose product code is already installed — "Another version of this product is
#    already installed. Installation of this version cannot continue." — which is what any rebuilt or re-uploaded
#    installer of the same version hits. Giving each package its own product code lets the upgrade machinery do its
#    job: the Upgrade table already covers the running version (VersionMax = this version, MaxInclusive), so
#    FindRelatedProducts sees the installed copy and RemoveExistingProducts takes it out before the new files land.
#    The package code is used as the product code: unique per build, and the same for a given file, so re-running one
#    installer still opens maintenance mode rather than reinstalling.
#
# 3. Replacing what is there. jpackage sequences RemoveExistingProducts before the install transaction even begins,
#    where Windows Installer will not run it, so the old copy was detected and then left behind — two entries of one
#    version in Installed apps. It is moved to just after InstallInitialize. Two custom actions run before it: one
#    closes the running app, which would otherwise be holding the files, one clears the runtime files that copy leaves
#    behind (its lock and port files, and the update cache) while keeping every setting the pilot made, and one clears
#    any BMS Companion shortcut already sitting on a desktop so installing again cannot leave a second icon behind.
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [Parameter(Mandatory = $true)][string]$Banner,
    [Parameter(Mandatory = $true)][string]$Dialog
)
$ErrorActionPreference = 'Stop'

foreach ($path in @($Msi, $Banner, $Dialog)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "not found: $path" }
}

# The parameter is $arguments, not $args, which PowerShell reserves. A call with no arguments passes $null: an empty
# array is a type mismatch for these COM methods.
function Invoke-Com($target, $name, $type, $arguments) { $target.GetType().InvokeMember($name, $type, $null, $target, $arguments) }

$msiPath = (Resolve-Path -LiteralPath $Msi).Path
$inst = New-Object -ComObject WindowsInstaller.Installer
try {
    # 1 = transact: the changes are written when Commit is called. One handle does the whole job — a second
    # OpenDatabase on the same file while this one is open fails.
    $db = Invoke-Com $inst 'OpenDatabase' 'InvokeMethod' @($msiPath, 1)
} catch {
    throw "cannot open $msiPath for writing (is it open somewhere else?): $($_.Exception.InnerException.Message)"
}

function Invoke-Sql($sql, $record) {
    $view = Invoke-Com $db 'OpenView' 'InvokeMethod' @($sql)
    if ($null -eq $record) { Invoke-Com $view 'Execute' 'InvokeMethod' $null | Out-Null }
    else { Invoke-Com $view 'Execute' 'InvokeMethod' @($record) | Out-Null }
    return $view
}

# ---------------- 1. the artwork ----------------
function Set-Bitmap($name, $file) {
    $rec = Invoke-Com $inst 'CreateRecord' 'InvokeMethod' @(1)
    Invoke-Com $rec 'SetStream' 'InvokeMethod' @(1, (Resolve-Path -LiteralPath $file).Path)
    $view = Invoke-Sql "UPDATE ``Binary`` SET ``Data`` = ? WHERE ``Name`` = '$name'" $rec
    Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
}
Set-Bitmap 'WixUI_Bmp_Banner' $Banner
Set-Bitmap 'WixUI_Bmp_Dialog' $Dialog

# ---------------- 2. a product code of this package's own ----------------
$si = Invoke-Com $db 'SummaryInformation' 'GetProperty' @(0)
$packageCode = Invoke-Com $si 'Property' 'GetProperty' @(9)
if ($packageCode -notmatch '^\{[0-9A-Fa-f-]{36}\}$') { throw "the package code is not a GUID: $packageCode" }

$view = Invoke-Sql "UPDATE ``Property`` SET ``Value`` = '$packageCode' WHERE ``Property`` = 'ProductCode'" $null
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null

# The upgrade range has to include the version being installed, or a second copy of it is installed alongside the
# first. jpackage writes attributes 257 = MigrateFeatures (0x001) + VersionMinInclusive (0x100) — and 0x100 is
# *Min*, not Max; VersionMaxInclusive is 0x200. With VersionMin empty that 0x100 does nothing, so VersionMax = this
# version was exclusive and an installed copy of the same version never matched. That is why upgrading 1.3.2 to 1.3.3
# replaced it while 1.3.3 over 1.3.3 quietly piled up a second entry in Installed apps.
$MAX_INCLUSIVE = 0x200
$view = Invoke-Sql 'SELECT UpgradeCode, VersionMin, VersionMax, Language, Attributes, Remove, ActionProperty FROM Upgrade WHERE ActionProperty = ''JP_UPGRADABLE_FOUND''' $null
$rec = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
if ($null -eq $rec) { throw "the installer has no JP_UPGRADABLE_FOUND row: it cannot replace an installed copy" }
$row = @{
    UpgradeCode = Invoke-Com $rec 'StringData' 'GetProperty' @(1)
    VersionMin  = Invoke-Com $rec 'StringData' 'GetProperty' @(2)
    VersionMax  = Invoke-Com $rec 'StringData' 'GetProperty' @(3)
    Language    = Invoke-Com $rec 'StringData' 'GetProperty' @(4)
    Attributes  = Invoke-Com $rec 'IntegerData' 'GetProperty' @(5)
    Remove      = Invoke-Com $rec 'StringData' 'GetProperty' @(6)
}
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
$versionMax = $row.VersionMax
$attributes = $row.Attributes
if (-not ($attributes -band $MAX_INCLUSIVE)) {
    # Attributes is part of the Upgrade table's primary key, so the row cannot be updated in place: it is replaced.
    $attributes = $attributes -bor $MAX_INCLUSIVE
    $view = Invoke-Sql "DELETE FROM ``Upgrade`` WHERE ``ActionProperty`` = 'JP_UPGRADABLE_FOUND'" $null
    Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null

    $rec = Invoke-Com $inst 'CreateRecord' 'InvokeMethod' @(7)
    Invoke-Com $rec 'StringData' 'SetProperty' @(1, $row.UpgradeCode)
    if ($row.VersionMin) { Invoke-Com $rec 'StringData' 'SetProperty' @(2, $row.VersionMin) }
    if ($row.VersionMax) { Invoke-Com $rec 'StringData' 'SetProperty' @(3, $row.VersionMax) }
    if ($row.Language) { Invoke-Com $rec 'StringData' 'SetProperty' @(4, $row.Language) }
    Invoke-Com $rec 'IntegerData' 'SetProperty' @(5, $attributes)
    if ($row.Remove) { Invoke-Com $rec 'StringData' 'SetProperty' @(6, $row.Remove) }
    Invoke-Com $rec 'StringData' 'SetProperty' @(7, 'JP_UPGRADABLE_FOUND')
    $view = Invoke-Sql 'INSERT INTO `Upgrade` (`UpgradeCode`, `VersionMin`, `VersionMax`, `Language`, `Attributes`, `Remove`, `ActionProperty`) VALUES (?, ?, ?, ?, ?, ?, ?)' $rec
    Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
    Write-Host "installer upgrade: the upgrade range now includes version $versionMax itself (attributes $attributes)"
}

# ---------------- 3. say which context this installs into ----------------
# jpackage writes neither ALLUSERS nor MSIINSTALLPERUSER, so the context was left undeclared: run with administrator
# rights the install registered per-machine, while the search for an older copy ran per-user and found nothing. Every
# install became a new product and they piled up in Installed apps. The context has to be stated — and stated as the
# one the package was built for.
function Set-Property($name, $value) {
    $view = Invoke-Sql "SELECT Value FROM Property WHERE Property = '$name'" $null
    $exists = $null -ne (Invoke-Com $view 'Fetch' 'InvokeMethod' $null)
    Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
    $sql = if ($exists) { "UPDATE ``Property`` SET ``Value`` = '$value' WHERE ``Property`` = '$name'" }
           else { "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('$name', '$value')" }
    $view = Invoke-Sql $sql $null
    Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
}
# ALLUSERS=1: for the machine, which is where every copy already installed is registered, so an update finds it.
#
# This only works because the package itself is built for the machine (perUserInstall = false in desktop/build.gradle.kts).
# Built the other way, jpackage sets bit 8 of the summary Word Count — "this package installs without elevation" — and
# a package that says that and then asks for ALLUSERS=1 is a contradiction Windows answers with "You do not have
# sufficient privileges to complete this installation for all users of the machine". It cannot be got round by
# elevating: the refusal is the point. It only looked fine on a PC with UAC switched off, where everything is already
# elevated. Per machine, double-clicking the installer raises the ordinary consent prompt on its own.
Set-Property 'ALLUSERS' '1'

# ---------------- 4. actually replace what is already installed ----------------
# jpackage sequences RemoveExistingProducts at 798, before CostInitialize and so before InstallInitialize — outside
# the install transaction, where Windows Installer does not run it. The old copy was detected and then left in place,
# which is how two entries of one version ended up in Installed apps. 1525 is just after InstallInitialize, the
# position Microsoft documents as "early" and the one WiX's own afterInstallInitialize uses.
$view = Invoke-Sql 'UPDATE `InstallExecuteSequence` SET `Sequence` = 1525 WHERE `Action` = ''RemoveExistingProducts''' $null
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null

# Anything still running holds the files it is being asked to replace. Type 98 = run an exe (2) from a directory in
# the package (32), and carry on whatever it returns (64) — taskkill reports failure when nothing was running.
# Both run between InstallValidate and InstallInitialize, as the installing user, so %APPDATA% is the right one.
function Add-Action($id, $target, $sequence) {
    # the same MSI can reach this script twice (packageMsi up to date, finishMsi re-run): start from a clean row
    foreach ($table in 'CustomAction', 'InstallExecuteSequence') {
        $view = Invoke-Sql ('DELETE FROM `{0}` WHERE `Action` = ''{1}''' -f $table, $id) $null
        Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
    }
    $rec = Invoke-Com $inst 'CreateRecord' 'InvokeMethod' @(4)
    Invoke-Com $rec 'StringData' 'SetProperty' @(1, $id)
    Invoke-Com $rec 'IntegerData' 'SetProperty' @(2, 98)
    Invoke-Com $rec 'StringData' 'SetProperty' @(3, 'TARGETDIR')
    Invoke-Com $rec 'StringData' 'SetProperty' @(4, $target)
    $view = Invoke-Sql 'INSERT INTO `CustomAction` (`Action`, `Type`, `Source`, `Target`) VALUES (?, ?, ?, ?)' $rec
    Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null

    $rec = Invoke-Com $inst 'CreateRecord' 'InvokeMethod' @(3)
    Invoke-Com $rec 'StringData' 'SetProperty' @(1, $id)
    Invoke-Com $rec 'StringData' 'SetProperty' @(2, 'NOT Installed')   # installing or upgrading, never uninstalling
    Invoke-Com $rec 'IntegerData' 'SetProperty' @(3, $sequence)
    $view = Invoke-Sql 'INSERT INTO `InstallExecuteSequence` (`Action`, `Condition`, `Sequence`) VALUES (?, ?, ?)' $rec
    Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
}

Add-Action 'JpCloseRunningApp' 'cmd.exe /c taskkill /F /IM "BMS Companion.exe" /T' 1450
# What a pilot set up is theirs, and an upgrade keeps it: the folders (BMS, EZBoards, HTML Briefing, screenshots),
# the VR boards, the Dashboard layouts, the network port. Only what belongs to the copy being replaced is cleared —
# the lock and port files of the instance just closed, and any installer sitting in the update cache, this one
# included. Settings are read defensively (unknown keys are ignored, a layout that no longer parses falls back to the
# default), so a file written by an older version cannot stop a newer one from starting.
Add-Action 'JpTidyRuntimeFiles' 'cmd.exe /c del /q /f "%APPDATA%\BMS Companion\app.lock" "%APPDATA%\BMS Companion\app.port" & rd /s /q "%APPDATA%\BMS Companion\updates"' 1460
# One desktop shortcut, however many times this is installed. The installer writes its shortcut to the shared desktop;
# a copy left on this user's own desktop by an earlier version (or by a version that installed per user) is a second
# icon pointing at a program that is no longer there. Every place a desktop can be — the shared one, the profile, and
# a profile whose desktop OneDrive has taken over — is cleared here, and the install then puts exactly one back.
Add-Action 'JpRemoveOldShortcuts' 'cmd.exe /c del /q "%PUBLIC%\Desktop\BMS Companion.lnk" "%USERPROFILE%\Desktop\BMS Companion.lnk" "%OneDrive%\Desktop\BMS Companion.lnk"' 1470

Invoke-Com $db 'Commit' 'InvokeMethod' $null | Out-Null

# ---------------- read it all back, so a silent no-op cannot pass for success ----------------
$expected = @{
    'WixUI_Bmp_Banner' = (Get-Item -LiteralPath $Banner).Length
    'WixUI_Bmp_Dialog' = (Get-Item -LiteralPath $Dialog).Length
}
$view = Invoke-Sql 'SELECT Name, Data FROM Binary' $null
$seen = @{}
while ($true) {
    $rec = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
    if ($null -eq $rec) { break }
    $name = Invoke-Com $rec 'StringData' 'GetProperty' @(1)
    if ($expected.ContainsKey($name)) { $seen[$name] = Invoke-Com $rec 'DataSize' 'GetProperty' @(2) }
}
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
foreach ($name in $expected.Keys) {
    if (-not $seen.ContainsKey($name)) { throw "$name is not in the installer's Binary table" }
    if ($seen[$name] -ne $expected[$name]) { throw "$name is $($seen[$name]) bytes, expected $($expected[$name])" }
    Write-Host "installer artwork: $name = $($seen[$name]) bytes"
}

$view = Invoke-Sql "SELECT Value FROM Property WHERE Property = 'ProductCode'" $null
$rec = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
$productCode = Invoke-Com $rec 'StringData' 'GetProperty' @(1)
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
if ($productCode -ne $packageCode) { throw "the product code is still $productCode" }
if (-not ($attributes -band $MAX_INCLUSIVE)) { throw "the upgrade range still excludes version $versionMax" }
Write-Host "installer upgrade: product code $productCode, replaces installed copies up to and including $versionMax"

# the three sequence rows that make an upgrade actually replace the old copy
$view = Invoke-Sql "SELECT Action, Sequence FROM InstallExecuteSequence WHERE Action = 'RemoveExistingProducts' OR Action = 'JpCloseRunningApp' OR Action = 'JpTidyRuntimeFiles' OR Action = 'JpRemoveOldShortcuts'" $null
$seen = @{}
while ($true) {
    $rec = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
    if ($null -eq $rec) { break }
    $seen[(Invoke-Com $rec 'StringData' 'GetProperty' @(1))] = Invoke-Com $rec 'IntegerData' 'GetProperty' @(2)
}
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
foreach ($a in 'JpCloseRunningApp', 'JpTidyRuntimeFiles', 'JpRemoveOldShortcuts', 'RemoveExistingProducts') {
    if (-not $seen.ContainsKey($a)) { throw "$a is missing from the install sequence" }
}
if ($seen['RemoveExistingProducts'] -lt 1500) { throw "RemoveExistingProducts is at $($seen['RemoveExistingProducts']), before the install transaction starts" }
if ($seen['JpCloseRunningApp'] -ge $seen['RemoveExistingProducts']) { throw "the app is closed too late to release its files" }
if ($seen['JpRemoveOldShortcuts'] -ge $seen['RemoveExistingProducts']) { throw "stale shortcuts are cleared too late" }
Write-Host "installer upgrade: closes the app at $($seen['JpCloseRunningApp']), clears the old copy's runtime files at $($seen['JpTidyRuntimeFiles']), clears old desktop shortcuts at $($seen['JpRemoveOldShortcuts']), removes the old copy at $($seen['RemoveExistingProducts'])"

# the context has to be stated, or an upgrade looks for the old copy somewhere it was never registered
$context = @{}
$view = Invoke-Sql "SELECT Property, Value FROM Property WHERE Property = 'ALLUSERS' OR Property = 'MSIINSTALLPERUSER'" $null
while ($true) {
    $rec = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
    if ($null -eq $rec) { break }
    $context[(Invoke-Com $rec 'StringData' 'GetProperty' @(1))] = Invoke-Com $rec 'StringData' 'GetProperty' @(2)
}
Invoke-Com $view 'Close' 'InvokeMethod' $null | Out-Null
if ($context['ALLUSERS'] -ne '1') { throw "the install context is not set: $($context | Out-String)" }
# ...and the package must not be one that declares it installs without elevation, or Windows refuses ALLUSERS=1 and
# the installer dies on "You do not have sufficient privileges" the moment UAC is on
$si = Invoke-Com $db 'SummaryInformation' 'GetProperty' @(0)   # through the open database: the file itself is locked here
$wordCount = Invoke-Com $si 'Property' 'GetProperty' @(15)
if ($wordCount -band 8) { throw "the package is built per user (Word Count $wordCount) and cannot install for the machine: set perUserInstall = false" }
Write-Host "installer upgrade: installs for the machine (ALLUSERS=1), and asks for consent by itself"
