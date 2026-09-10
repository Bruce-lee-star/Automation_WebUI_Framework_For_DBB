# Fixes test-automation imports broken by the lifecycle sub-package split.
# - rewrites old FQN imports (...lifecycle.<Class>) to the new sub-package FQN
# - adds missing imports for sub-package classes referenced by the test body
# Writes UTF8 without BOM.

$root = 'd:\IdeaProject\Automation_WebUI_Framework_For_DBB'
$lc = Join-Path $root 'web\src\main\java\com\hsbc\cmb\hk\dbb\automation\framework\web\lifecycle'
$ta = Join-Path $root 'test-automation\src\test\java\com\hsbc\cmb\hk\dbb\automation\framework\web\lifecycle'
$pkgPrefix = 'com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.'
$enc = New-Object System.Text.UTF8Encoding($false)

# 1) build map: class name -> new FQN (sub-package classes only)
$map = @{}
Get-ChildItem -Path $lc -Recurse -Filter *.java | ForEach-Object {
    $rel = $_.FullName.Substring($lc.Length + 1)
    if ($rel -notlike '*\*') { return }
    $base = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
    $sub = ([System.IO.Path]::GetDirectoryName($rel)) -replace '\\', '.'
    $map[$base] = $pkgPrefix + $sub + '.' + $base
}

Write-Output ('Mapped ' + $map.Count + ' sub-package classes')

Get-ChildItem -Path $ta -Recurse -Filter *.java | ForEach-Object {
    $path = $_.FullName
    $text = [System.IO.File]::ReadAllText($path)
    $orig = $text
    $add = New-Object System.Collections.ArrayList

    foreach ($cls in ($map.Keys | Sort-Object)) {
        $fqn = $map[$cls]
        $oldFqn = $pkgPrefix + $cls
        if ($text -match [regex]::Escape('import ' + $fqn + ';')) { continue }
        if ($text -match [regex]::Escape('import ' + $oldFqn + ';')) {
            $text = $text -replace [regex]::Escape('import ' + $oldFqn + ';'), ('import ' + $fqn + ';')
            continue
        }
        $bodyLines = ($text -split "`r?`n") | Where-Object { $_ -notmatch '^\s*import ' }
        $bodyText = $bodyLines -join "`n"
        if ($bodyText -match ('\b' + [regex]::Escape($cls) + '\b')) {
            [void]$add.Add('import ' + $fqn + ';')
        }
    }

    if (($text -ne $orig) -or ($add.Count -gt 0)) {
        $lines = $text -split "`r?`n"
        $out = New-Object System.Collections.ArrayList
        $inserted = $false
        foreach ($ln in $lines) {
            if (-not $inserted -and $ln -match '^\s*import ') {
                foreach ($a in $add) { [void]$out.Add($a) }
                $inserted = $true
            }
            [void]$out.Add($ln)
        }
        if (-not $inserted) {
            $out2 = New-Object System.Collections.ArrayList
            foreach ($ln in $lines) {
                [void]$out2.Add($ln)
                if ($ln -match '^\s*package ') {
                    [void]$out2.Add('')
                    foreach ($a in $add) { [void]$out2.Add($a) }
                }
            }
            $out = $out2
        }
        [System.IO.File]::WriteAllText($path, ($out -join "`r`n"), $enc)
        Write-Output ('UPDATED: ' + $_.Name + ' added=' + $add.Count + ' rewritten=' + ($text -ne $orig))
    }
}
