# T4-4 doc-drift check (PowerShell). Mirrors tools/check-doc-drift.sh.
# Kept ASCII-only on purpose: Windows PowerShell mis-parses non-ASCII .ps1 without BOM.
$ErrorActionPreference = 'Stop'
$ROOT = Resolve-Path (Join-Path $PSScriptRoot '..')
$POM = Join-Path $ROOT 'pom.xml'
$README = Join-Path $ROOT 'README.md'

if (-not (Test-Path $POM) -or -not (Test-Path $README)) {
  Write-Error "Cannot find pom.xml or README.md (ROOT=$ROOT)"
  exit 2
}

$FIX = $args -contains '--fix'

# label -> pom property name
$MAP = @(
  @('Playwright', 'playwright.version'),
  @('Serenity', 'serenity.version'),
  @('serenity-maven-plugin', 'serenity.version'),
  @('Cucumber', 'cucumber.version'),
  @('JUnit', 'junit.version'),
  @('Logback', 'logback.version'),
  @('typesafe.config', 'typesafe.config.version'),
  @('Gson', 'gson.version'),
  @('JsonPath', 'json.path.version')
)

function Get-Prop([string]$prop) {
  $xml = [xml](Get-Content $POM -Encoding UTF8 -Raw)
  $node = $xml.project.properties.($prop)
  if ($null -eq $node) { return $null }
  return $node.ToString()
}

$rc = 0
$lines = Get-Content $README -Encoding UTF8

foreach ($entry in $MAP) {
  $label = $entry[0]; $prop = $entry[1]
  $expected = Get-Prop $prop
  if (-not $expected) { Write-Warning "Property ($prop) not found in pom; skip $label"; continue }

  if ($FIX) {
    $lines = $lines | ForEach-Object {
      $line = $_
      if ($line -match [regex]::Escape($label)) {
        $m = [regex]::Match($line, [regex]::Escape($label) + '(?:(?!\d+\.\d+\.\d+).)*?(\d+\.\d+\.\d+)')
        if ($m.Success) { $line = $line.Replace($m.Groups[1].Value, $expected) }
      }
      $line
    }
    Write-Host "FIX : $label -> $expected (pom $prop)"
  } else {
    if (($lines -join "`n") -match [regex]::Escape($expected)) {
      Write-Host "OK   : $label = $expected (pom $prop)"
    } else {
      Write-Error "FAIL : $label version $expected not found in README (pom $prop); run 'pwsh tools/check-doc-drift.ps1 --fix' or sync manually"
      $rc = 1
    }
  }
}

if ($FIX) {
  $text = $lines -join "`r`n"
  if (-not $text.EndsWith("`r`n")) { $text += "`r`n" }
  # UTF-8 without BOM (PowerShell 5.1 Set-Content -Encoding UTF8 adds a BOM, which would pollute the repo)
  [System.IO.File]::WriteAllText($README, $text, (New-Object System.Text.UTF8Encoding $false))
}

# Spring is NOT a framework dependency (only route-demo-* demo services use Spring Boot).
if (-not $FIX) {
  if (($lines -join "`n") -match 'Spring Context 6\.1\.6') {
    Write-Error "FAIL : README still lists Spring Context 6.1.6 as a framework dependency (framework has no Spring; only demo uses it)"
    $rc = 1
  }
} else {
  if (($lines -join "`n") -match 'Spring Context 6\.1\.6') {
    Write-Warning "README still contains a 'Spring Context' framework dependency row; remove it manually (framework has no Spring)"
  }
}

if (-not $FIX) {
  if ($rc -eq 0) { Write-Host "Doc drift check PASSED" }
  else { Write-Error "Doc drift check FAILED (README drifts from pom)" }
}
exit $rc
