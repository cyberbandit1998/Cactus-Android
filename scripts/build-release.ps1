<#
.SYNOPSIS
  Builds a signed release APK: dist\CactusAI-release.apk
  Needs keystore.properties in the project root (see docs\RELEASE_SIGNING.md). Your keystore is never committed.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path 'keystore.properties')) { throw 'keystore.properties is missing. Create it as described in docs\RELEASE_SIGNING.md first.' }
if (-not (Test-Path 'native-engine\android-arm64\libneedle.a')) { & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\fetch-engine.ps1"; if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the engine.' } }

& .\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }

$built = Get-ChildItem 'app\build\outputs\apk\release' -Filter '*.apk' | Select-Object -First 1
if (-not $built) { throw 'No release APK was produced.' }
if ($built.Name -like '*unsigned*') { throw 'The APK is unsigned: check storeFile/keyAlias in keystore.properties.' }
New-Item -ItemType Directory -Force -Path dist | Out-Null
Copy-Item $built.FullName 'dist\CactusAI-release.apk' -Force

$sdk = $env:ANDROID_HOME; if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Recurse -Filter 'apksigner.bat' -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1
if ($apksigner) { & $apksigner.FullName verify --verbose 'dist\CactusAI-release.apk' } else { Write-Host 'apksigner not found; skipping the signature check.' }
Write-Host 'Built dist\CactusAI-release.apk' -ForegroundColor Green
