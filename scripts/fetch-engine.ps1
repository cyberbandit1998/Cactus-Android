<#
.SYNOPSIS
  Downloads the official Needle engine for Android (arm64) into native-engine\android-arm64\.

.DESCRIPTION
  Source: Hugging Face repo Cactus-Compute/needle3, folder android-arm64/ (what the upstream
  `needle download android-arm64` command fetches). Files are discovered through the Hugging Face API
  rather than guessed and are checked against the SHA-256 the API publishes for large files.

  Run from a normal PowerShell:   powershell -ExecutionPolicy Bypass -File scripts\fetch-engine.ps1
#>
param(
  [string]$Repo = 'Cactus-Compute/needle3',
  [string]$Folder = 'android-arm64',
  [string]$Revision = 'main'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is much faster without the progress bar

$Root = Split-Path -Parent $PSScriptRoot
$Dest = Join-Path $Root "native-engine\$Folder"
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
$Api = "https://huggingface.co/api/models/$Repo"

Write-Host "Engine source: $Repo @ $Revision, folder $Folder"
$Commit = ''
try {
  $Commit = (Invoke-RestMethod -Uri "$Api/revision/$Revision").sha
  if ($Commit) { Write-Host "Resolved commit: $Commit"; $Revision = $Commit }
} catch { Write-Host "Could not resolve the revision to a commit; using '$Revision'." }

function Get-Folder([string]$Path) {
  $entries = Invoke-RestMethod -Uri "$Api/tree/$Revision/$Path"
  foreach ($e in $entries) {
    if ($e.type -eq 'directory') { Get-Folder $e.path; continue }
    if ($e.type -ne 'file') { continue }
    $name = Split-Path -Leaf $e.path
    $target = Join-Path $Dest $name
    $size = if ($e.lfs) { $e.lfs.size } else { $e.size }
    Write-Host "  downloading $($e.path) ($size bytes)"
    Invoke-WebRequest -Uri "https://huggingface.co/$Repo/resolve/$Revision/$($e.path)" -OutFile "$target.part" -UseBasicParsing
    if ($e.lfs -and $e.lfs.oid) {
      $actual = (Get-FileHash -Algorithm SHA256 -Path "$target.part").Hash.ToLowerInvariant()
      if ($actual -ne $e.lfs.oid.ToLowerInvariant()) {
        Remove-Item -Force "$target.part"
        throw "SHA-256 mismatch for $($e.path)"
      }
    }
    Move-Item -Force "$target.part" $target
  }
}

Get-Folder $Folder

@(
  "repo: $Repo",
  "folder: $Folder",
  "commit: $(if ($Commit) { $Commit } else { 'unknown' })",
  "fetched: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))"
) | Set-Content -Path (Join-Path $Dest 'ENGINE_SOURCE.txt') -Encoding ASCII

Write-Host ''
Write-Host "Files in ${Dest}:"
Get-ChildItem $Dest | Format-Table Name, Length -AutoSize | Out-String | Write-Host
if (-not (Get-ChildItem -Path $Dest -Filter '*.a' -ErrorAction SilentlyContinue)) {
  Write-Error "No static library (*.a) was found in $Folder\. The upstream layout may have changed. Alternative: pip install cactus-needle; needle download android-arm64 --out native-engine"
  exit 1
}
Write-Host 'OK: engine library present.'
