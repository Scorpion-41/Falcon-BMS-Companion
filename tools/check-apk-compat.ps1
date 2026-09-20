# Checks a built APK for Java/Android APIs that do not exist on the oldest Android the app supports (minSdk 26).
#
# Why: the app compiles against API 35 (Android 15), which added the Java 21 "sequenced collections"
# (LinkedHashSet.reversed(), List.getFirst(), MutableList.removeFirst() …). Kotlin then binds those *members*
# instead of its own extensions, the build succeeds, lint stays quiet, and the app dies at runtime on older
# Android with NoSuchMethodError. 1.3.1 crashed on Android 9 for exactly that reason.
#
# Usage:  powershell -ExecutionPolicy Bypass -File tools\check-apk-compat.ps1 dist\BMS-Companion.apk
# Exit code 1 and a list of offending references when something is found.
param([string]$Apk = "dist\BMS-Companion.apk")
$ErrorActionPreference = "Stop"
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

# Type names that only exist from Android 15 (API 35). A reference to one means a Java 21 collection method is called.
$banned = @("SequencedCollection", "SequencedSet", "SequencedMap")

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk))
$found = @()
try {
    foreach ($entry in $zip.Entries | Where-Object { $_.Name -like "classes*.dex" }) {
        $stream = $entry.Open()
        $mem = New-Object System.IO.MemoryStream
        $stream.CopyTo($mem)
        $stream.Dispose()
        $text = [System.Text.Encoding]::ASCII.GetString($mem.ToArray())
        $mem.Dispose()
        foreach ($name in $banned) { if ($text.Contains($name)) { $found += "$($entry.Name): $name" } }
    }
} finally { $zip.Dispose() }

if ($found.Count -gt 0) {
    Write-Host "APK uses APIs that are missing before Android 15:" -ForegroundColor Red
    $found | ForEach-Object { Write-Host "  $_" }
    Write-Host "Replace the call with a Kotlin equivalent, e.g. set.toList().asReversed() instead of set.reversed()."
    exit 1
}
Write-Host "$Apk : no API-35-only collection calls found (safe back to Android 8)."
