$j = Get-Content (Join-Path $PSScriptRoot 'osv-scan-result.json') -Raw | ConvertFrom-Json
$rank = @{CRITICAL=4;HIGH=3;MODERATE=2;MEDIUM=2;LOW=1;UNKNOWN=0}
$rows = @()
foreach ($r in $j.results) {
  $src = if ($r.source -and $r.source.path) { $r.source.path } elseif ($r.source -and $r.source.name) { $r.source.name } else { '?' }
  $mod = Split-Path $src | Split-Path -Leaf
  foreach ($p in $r.packages) {
    foreach ($v in $p.vulnerabilities) {
      $sev = 'UNKNOWN'
      if ($v.database_specific -and $v.database_specific.severity) { $sev = [string]$v.database_specific.severity }
      $fix = @()
      foreach ($a in $v.affected) { foreach ($rg in $a.ranges) { foreach ($e in $rg.events) { if ($e -and $e.fixed) { $fix += $e.fixed } } } }
      $fix = ($fix | Sort-Object -Unique) -join ','
      $rows += [PSCustomObject]@{rank=$rank[$sev];sev=$sev;mod=$mod;pkg="$($p.package.name)@$($p.package.version)";id=$v.id;fix=$fix}
    }
  }
}
echo "===== PER-MODULE SUMMARY ====="
$rows | Group-Object mod | ForEach-Object {
  $g = $_.Group
  $c = ($g | Where-Object { $_.sev -eq 'CRITICAL' }).Count
  $h = ($g | Where-Object { $_.sev -eq 'HIGH' }).Count
  $m = ($g | Where-Object { $_.sev -eq 'MODERATE' -or $_.sev -eq 'MEDIUM' }).Count
  $l = ($g | Where-Object { $_.sev -eq 'LOW' }).Count
  [PSCustomObject]@{module=$_.Name; total=$_.Count; CRIT=$c; HIGH=$h; MOD=$m; LOW=$l}
} | Sort-Object @{Expression='CRIT';Descending=$true}, @{Expression='HIGH';Descending=$true} | Format-Table -AutoSize
echo "===== DISTINCT VULNERABLE PACKAGES (by module) ====="
$rows | Group-Object mod, pkg | ForEach-Object {
  $parts = $_.Name -split ', '
  "$($parts[0]) | $($parts[1]) | vulns=$($_.Count)"
} | Sort-Object
echo "TOTAL_VULNS=$($rows.Count)"
