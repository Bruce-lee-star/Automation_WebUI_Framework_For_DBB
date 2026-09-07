# Download OSV-Scanner Windows binary into tools/osv-scanner/ (git-ignored, see .gitignore).
# CI / dev bootstrap for the T1-4 CVE gate.
$ErrorActionPreference = 'Stop'
$ver = '2.5.0'
$destDir = Join-Path $PSScriptRoot 'osv-scanner'
New-Item -ItemType Directory -Force $destDir | Out-Null
$exe = Join-Path $destDir 'osv-scanner.exe'
if (Test-Path $exe) { Write-Host "[install] already present: $exe"; exit 0 }
$url = "https://github.com/google/osv-scanner/releases/download/v$ver/osv-scanner_windows_amd64.exe"
Write-Host "[install] downloading $url"
Invoke-WebRequest -Uri $url -OutFile $exe -MaximumRedirection 10
Write-Host "[install] done: $exe"
