# Builds the BMS Companion PC app (Windows) into dist/:
#   dist/BMS-Companion-PC.msi  installer (per-user, Start menu + desktop shortcut)
#   dist/BMS-Companion-PC.zip  portable folder (unzip and run "BMS Companion.exe")
# Both include their own Java runtime and the browser version, so users install nothing else.
# packageMsi finishes by running finish-msi.ps1: the installer's artwork, and a product code of its own so it can be
# installed over a copy that is already there.
# Usage (from the repo root):  powershell -ExecutionPolicy Bypass -File pc\publish-desktop.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "dist"

# installer + app folder (Compose for Desktop / jpackage)
Push-Location $root
try {
    & .\gradlew.bat ":desktop:packageMsi" ":desktop:createDistributable" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally { Pop-Location }

$bin = Join-Path $root "desktop\build\compose\binaries\main"
$msi = Get-ChildItem (Join-Path $bin "msi") -Filter *.msi | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $msi.FullName (Join-Path $out "BMS-Companion-PC.msi") -Force

$zip = Join-Path $out "BMS-Companion-PC.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
# .NET ZipFile rather than Compress-Archive, which fails on the read-only jars jpackage writes
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $bin "app\BMS Companion"), $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)

Write-Host "Built $out\BMS-Companion-PC.msi and $out\BMS-Companion-PC.zip"
