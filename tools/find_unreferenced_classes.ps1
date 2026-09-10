# Scans for public types that are never referenced outside their own declaration file.
# Pure ASCII output. Reports candidates only - each needs manual confirmation
# (SPI / reflection / config / ArchUnit rule references are not detected).

$root = 'd:\IdeaProject\Automation_WebUI_Framework_For_DBB'
$srcDirs = @()
foreach ($m in @('core', 'web', 'api', 'reporting', 'route', 'codegen', 'test-automation')) {
    $d = Join-Path $root "$m\src"
    if (Test-Path $d) { $srcDirs += $d }
}

# collect all java files
$files = @()
foreach ($d in $srcDirs) { $files += (Get-ChildItem -Path $d -Recurse -Filter *.java) }

# map: simple name -> list of declaring files
$decls = @{}
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName)
    foreach ($m in [regex]::Matches($txt, '(?m)^\s*(?:public\s+|abstract\s+|final\s+)*(?:class|interface|enum|record)\s+(\w+)')) {
        $n = $m.Groups[1].Value
        if (-not $decls.ContainsKey($n)) { $decls[$n] = New-Object System.Collections.ArrayList }
        [void]$decls[$n].Add($f.FullName)
    }
}

Write-Output ('Total java files: ' + $files.Count + ', total types: ' + $decls.Count)
Write-Output '--- CANDIDATES (referenced only in own file, or nowhere) ---'

foreach ($name in ($decls.Keys | Sort-Object)) {
    # skip duplicate simple names (ambiguous) and test classes
    if ($decls[$name].Count -gt 1) { continue }
    if ($name -like '*Test' -or $name -like '*IT' -or $name -like 'Test*') { continue }

    $declFile = $decls[$name][0]
    $refCount = 0
    foreach ($f in $files) {
        if ($f.FullName -eq $declFile) { continue }
        $txt = [System.IO.File]::ReadAllText($f.FullName)
        if ($txt -match ('\b' + [regex]::Escape($name) + '\b')) { $refCount++ }
    }
    if ($refCount -eq 0) {
        Write-Output ('UNREFERENCED: ' + $name + '  [' + $declFile.Substring($root.Length + 1) + ']')
    }
}
