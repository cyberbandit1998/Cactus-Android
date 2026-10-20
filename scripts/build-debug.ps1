<#
.SYNOPSIS
  Builds the installable debug APK: dist\CactusAI-debug.apk

  powershell -ExecutionPolicy Bypass -File scripts\build-debug.ps1
  Then, with the phone connected and USB debugging on:  adb install -r dist\CactusAI-debug.apk
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path 'native-engine\android-arm64\libneedle.a')) {
  Write-Host 'Fetching the Needle engine first...'
  & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\fetch-engine.ps1"
  if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the engine.' }
}

# Android Studio's bundled JDK is fine; use it when no suitable java is on PATH.
if (-not $env:JAVA_HOME) {
  $studio = @("$env:ProgramFiles\Android\Android Studio", "$env:LOCALAPPDATA\Programs\Android Studio") | Where-Object { Test-Path "$_\jbr" } | Select-Object -First 1
  if ($studio) { $env:JAVA_HOME = "$studio\jbr"; Write-Host "Using Android Studio's JDK: $env:JAVA_HOME" }
}

& .\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed. See the messages above, and the README troubleshooting section.' }

$apk = Join-Path $root 'dist\CactusAI-debug.apk'
if (-not (Test-Path $apk)) { throw "The build finished but $apk was not created." }
$item = Get-Item $apk
Write-Host ''
Write-Host ("Built {0} ({1:N1} MB)" -f $item.FullName, ($item.Length / 1MB)) -ForegroundColor Green
Write-Host 'Install on a connected phone:   adb install -r dist\CactusAI-debug.apk'
