<#
.SYNOPSIS
  Checks that a Windows PC can build Cactus AI, and says exactly what is missing.
  It only reads; it installs nothing.

  powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1
#>
$ErrorActionPreference = 'Continue'
$problems = 0

function Show($ok, $name, $detail, $fix) {
  if ($ok) { Write-Host ("  [ ok ] {0,-22} {1}" -f $name, $detail) -ForegroundColor Green }
  else { $script:problems++; Write-Host ("  [MISS] {0,-22} {1}" -f $name, $detail) -ForegroundColor Red; if ($fix) { Write-Host ("         fix: {0}" -f $fix) -ForegroundColor Yellow } }
}

Write-Host 'Cactus AI build environment'
Write-Host ('  PowerShell {0}, {1}' -f $PSVersionTable.PSVersion, [Environment]::OSVersion.VersionString)

# JDK 17 or newer (Android Studio bundles one; its folder is "jbr")
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
$javaVersion = $null
if ($javaCmd) { $javaVersion = (& java -version 2>&1 | Select-Object -First 1) }
$studio = @("$env:ProgramFiles\Android\Android Studio", "${env:ProgramFiles(x86)}\Android\Android Studio", "$env:LOCALAPPDATA\Programs\Android Studio") | Where-Object { Test-Path $_ } | Select-Object -First 1
$jbr = if ($studio) { Join-Path $studio 'jbr' } else { $null }
$hasJdk = ($javaCmd -and $javaVersion -match '"(1[7-9]|2\d)') -or ($jbr -and (Test-Path (Join-Path $jbr 'bin\java.exe')))
Show $hasJdk 'JDK 17+' ($(if ($javaVersion) { $javaVersion } elseif ($jbr) { "Android Studio's bundled JDK at $jbr" } else { 'not found' })) 'winget install EclipseAdoptium.Temurin.17.JDK   (or just install Android Studio, which bundles one)'

Show ([bool]$studio) 'Android Studio' ($(if ($studio) { $studio } else { 'not found' })) 'winget install Google.AndroidStudio'

# Android SDK
$sdk = $env:ANDROID_HOME; if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $default = "$env:LOCALAPPDATA\Android\Sdk"; if (Test-Path $default) { $sdk = $default } }
Show ($sdk -and (Test-Path $sdk)) 'Android SDK' ($(if ($sdk) { $sdk } else { 'not found' })) 'Open Android Studio once and finish the setup wizard (installs the SDK).'
if ($sdk -and (Test-Path $sdk)) {
  $platform = Get-ChildItem (Join-Path $sdk 'platforms') -ErrorAction SilentlyContinue | Where-Object Name -eq 'android-35'
  Show ([bool]$platform) 'SDK Platform 35' ($(if ($platform) { 'present' } else { 'absent (Gradle downloads it on the first build if licences are accepted)' })) 'Android Studio > SDK Manager > SDK Platforms > Android 15 (API 35)'
  $ndk = Get-ChildItem (Join-Path $sdk 'ndk') -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
  Show ([bool]$ndk) 'NDK (side by side)' ($(if ($ndk) { $ndk.Name } else { 'absent (Gradle downloads it on the first build if licences are accepted)' })) 'Android Studio > SDK Manager > SDK Tools > NDK (Side by side)'
  $cmake = Get-ChildItem (Join-Path $sdk 'cmake') -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
  Show ([bool]$cmake) 'CMake' ($(if ($cmake) { $cmake.Name } else { 'absent (Gradle downloads it on the first build if licences are accepted)' })) 'Android Studio > SDK Manager > SDK Tools > CMake 3.22.1'
  $adb = Join-Path $sdk 'platform-tools\adb.exe'
  Show (Test-Path $adb) 'adb' ($(if (Test-Path $adb) { $adb } else { 'absent' })) 'Android Studio > SDK Manager > SDK Tools > Android SDK Platform-Tools'
}

$git = Get-Command git -ErrorAction SilentlyContinue
Show ([bool]$git) 'Git' ($(if ($git) { (& git --version) } else { 'not found' })) 'winget install Git.Git'

$py = Get-Command python -ErrorAction SilentlyContinue
Write-Host ('  [info] Gradle: not needed separately, the project ships a wrapper (gradlew.bat).')
Write-Host ('  [info] Rust / Cargo: NOT used by this project.')
if ($py) { Write-Host ('  [info] Python found ({0}); only needed if you prefer the official "needle download" command over scripts\fetch-engine.ps1.' -f (& python --version)) }

$engine = Join-Path (Split-Path -Parent $PSScriptRoot) 'native-engine\android-arm64\libneedle.a'
Show (Test-Path $engine) 'Needle engine' ($(if (Test-Path $engine) { $engine } else { 'not fetched yet' })) 'powershell -ExecutionPolicy Bypass -File scripts\fetch-engine.ps1'

Write-Host ''
if ($problems -eq 0) { Write-Host 'Everything needed is in place. Next: scripts\build-debug.ps1' -ForegroundColor Green }
else { Write-Host "$problems item(s) need attention (see the fixes above)." -ForegroundColor Yellow }
