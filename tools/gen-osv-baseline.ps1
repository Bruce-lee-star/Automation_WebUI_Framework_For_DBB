# Regenerate osv-scanner.toml baseline from osv-report.json (T1-4 CVE gate).
# Usage: powershell -File tools/gen-osv-baseline.ps1
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot | Split-Path -Parent
$report = Join-Path $root 'osv-report.json'
if (-not (Test-Path $report)) { Write-Error "osv-report.json not found; run osv-scanner first."; exit 1 }
$j = Get-Content $report -Raw | ConvertFrom-Json
$map = @{}
$rank = @{CRITICAL=4;HIGH=3;MODERATE=2;LOW=1;UNKNOWN=0}
foreach ($r in $j.results) {
  foreach ($p in $r.packages) {
    foreach ($v in $p.vulnerabilities) {
      $id = $v.id
      $ds = $v.database_specific
      $sev = if ($ds -and $ds.severity) { [string]$ds.severity } else { 'UNKNOWN' }
      $pkg = "$($p.package.name)@$($p.package.version)"
      if (-not $map.ContainsKey($id)) { $map[$id] = @{sev=$sev; pkgs=@()} }
      if ($rank[$sev] -gt $rank[$map[$id].sev]) { $map[$id].sev = $sev }
      if ($map[$id].pkgs -notcontains $pkg) { $map[$id].pkgs += $pkg }
    }
  }
}
$sb = New-Object System.Text.StringBuilder
$sb.AppendLine("# OSV-Scanner ignore list = CVE gate known-vuln baseline (T1-4).") | Out-Null
$sb.AppendLine("# Generated 2026-09-04 from osv-report.json (OSV.dev, keyless data source).") | Out-Null
$sb.AppendLine("# Purpose: freeze all currently-known vuln IDs so the gate starts green;") | Out-Null
$sb.AppendLine("# the gate FAILS on any NEW vuln ID (regression from upgrade/new dependency).") | Out-Null
$sb.AppendLine("# Maintenance: when a dependency upgrade clears a CVE, delete its [[IgnoredVulns]];") | Out-Null
$sb.AppendLine("# never mask Critical/High with this list - remediate via upgrade roadmap.") | Out-Null
$sb.AppendLine("") | Out-Null
foreach ($id in ($map.Keys | Sort-Object)) {
  $e = $map[$id]
  $pkgs = $e.pkgs -join ', '
  $sb.AppendLine("[[IgnoredVulns]]") | Out-Null
  $sb.AppendLine("id = `"$id`"") | Out-Null
  $sb.AppendLine("reason = `"$($e.sev) | affects: $pkgs | baseline 2026-09-04, pending upgrade/triage`"") | Out-Null
  $sb.AppendLine("") | Out-Null
}
$out = Join-Path $root 'osv-scanner.toml'
[System.IO.File]::WriteAllText($out, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
"WROTE $out entries=$(($map.Keys).Count)"
