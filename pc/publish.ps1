# Builds BMSCompanionBridge.exe as a single self-contained file (no .NET install needed on the user's PC).
# Usage (from the repo root):  powershell -ExecutionPolicy Bypass -File pc\publish.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "dist"
dotnet publish (Join-Path $PSScriptRoot "BmsCompanionBridge\BmsCompanionBridge.csproj") `
    -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
    -p:DebugType=none -o (Join-Path $out "bridge")
Copy-Item (Join-Path $out "bridge\BMSCompanionBridge.exe") (Join-Path $out "BMSCompanionBridge.exe") -Force
Remove-Item (Join-Path $out "bridge") -Recurse -Force
Write-Host "Built $out\BMSCompanionBridge.exe"
