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
  # 2026-10 JUnit5 -> JUnit4 统一：根 pom 现定义 <junit.version>（4.13.2），
  # 原先的 junit.jupiter.version / junit.platform.version 与 README 的 Platform 行一并移除。
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

# -- CT2-23: doc path references + module topology gates -----------------------
# The version gate alone did not validate module names / doc path references, so doc drift caused by
# renames (e.g. CT2-22) had no gate at all. Two checks are added:
#   1) reference integrity: every docs/**.md path referenced by pom.xml / README.md must exist on disk;
#   2) module topology: every <module> in the root pom must resolve to a directory holding a pom.xml.
if (-not $FIX) {
  $pomXml = [xml](Get-Content $POM -Encoding UTF8 -Raw)

  $docRefs = @()
  $rangeRaw = @()
  foreach ($src in @($POM, $README)) {
    $text = Get-Content $src -Encoding UTF8 -Raw
    foreach ($m in [regex]::Matches($text, 'docs/\S+?\.md')) {
      $docRefs += $m.Value
    }
    # N-09 fix: the README references docs by [range] (backtick docs/<dir>/<NN> backtick ~ backtick <MM>),
    #   NOT by exact docs/**.md paths. The old gate only matched the latter -> zero matches -> the loop
    #   body never ran -> the gate could never fail (dangling refs / renames went unnoticed). ASCII-only
    #   on purpose: Windows PowerShell mis-parses non-ASCII .ps1 without BOM.
    foreach ($m in [regex]::Matches($text, 'docs/[A-Za-z0-9_\-/]+/[0-9]{2}`?[ ]*~[ ]*`?[0-9]{2}')) {
      $rangeRaw += $m.Value
    }
  }
  foreach ($ref in ($docRefs | Sort-Object -Unique)) {
    if (Test-Path (Join-Path $ROOT $ref)) {
      Write-Host "OK   : doc ref exists: $ref"
    } else {
      Write-Error "FAIL : dangling doc reference '$ref' (referenced by pom.xml/README.md but missing on disk)"
      $rc = 1
    }
  }

  # N-09: expand each range ref (docs/dir/01~13 -> require dir/01*.md ... dir/13*.md to exist on disk).
  $rangeCount = 0
  foreach ($raw in ($rangeRaw | Sort-Object -Unique)) {
    $norm = ($raw -replace '[` ]', '')
    if ($norm -notmatch '^docs/[A-Za-z0-9_\-/]+/[0-9]{2}~[0-9]{2}$') {
      continue
    }
    $rpath = $norm.Split('~')[0]
    $rdir = $rpath.Substring(0, $rpath.LastIndexOf('/'))
    $rfrom = [int]$rpath.Substring($rpath.LastIndexOf('/') + 1)
    $rto = [int]$norm.Split('~')[1]
    if ($rfrom -gt $rto) {
      Write-Error "FAIL : doc range has reversed bounds: $raw"
      $rc = 1
      continue
    }
    $rangeCount++
    if (-not (Test-Path (Join-Path $ROOT $rdir))) {
      Write-Error "FAIL : doc range points to a missing directory: $rdir (from '$raw')"
      $rc = 1
      continue
    }
    $missing = @()
    for ($n = $rfrom; $n -le $rto; $n++) {
      $nn = '{0:d2}' -f $n
      if (-not (Get-ChildItem -Path (Join-Path $ROOT $rdir) -Filter "$nn*.md" -File -ErrorAction SilentlyContinue)) {
        $missing += $nn
      }
    }
    if ($missing.Count -gt 0) {
      Write-Error "FAIL : doc range $rdir/$($rfrom.ToString('00'))~$($rto.ToString('00')) misses document(s): $($missing -join ', ')"
      $rc = 1
    } else {
      Write-Host "OK   : doc range resolves: $rdir/$($rfrom.ToString('00'))~$($rto.ToString('00'))"
    }
  }

  # N-09 anti-vacuous guard: if NO reference matched at all, fail. A gate whose regex has drifted away
  #   from the actual docs style would otherwise pass silently forever -- and a gate that always passes
  #   is more dangerous than no gate at all (it manufactures false confidence).
  # 3653e6ac (chore(docs): remove architecture-review / ai-agent-design) deleted docs/** on purpose but
  #   left README pointing at it -> this guard failed ever since AND the reference was dangling. The repo
  #   now has no in-repo docs to reference, so zero matches is the EXPECTED state: warn instead of fail.
  #   The gate stays meaningful -- the moment any docs/**.md or range ref appears again, the resolution
  #   checks above run and a missing/dangling document still fails the build.
  if ($docRefs.Count -eq 0 -and $rangeCount -eq 0) {
    Write-Warning "SKIP: no in-repo doc reference (docs/** intentionally removed in 3653e6ac); resolution checks stay inactive until README/pom references a docs path again"
  }

  foreach ($mod in @($pomXml.project.modules.module)) {
    $modPom = Join-Path $ROOT (Join-Path $mod 'pom.xml')
    if (Test-Path $modPom) {
      Write-Host "OK   : module resolves: $mod"
    } else {
      Write-Error "FAIL : <module>$mod</module> declared in root pom but $modPom is missing"
      $rc = 1
    }
  }
}

if (-not $FIX) {
  if ($rc -eq 0) { Write-Host "Doc drift check PASSED" }
  else { Write-Error "Doc drift check FAILED (README drifts from pom)" }
}
exit $rc
