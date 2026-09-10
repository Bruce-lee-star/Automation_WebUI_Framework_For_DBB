# Removes lifecycle sub-package imports that are not referenced by real code
# (they may only appear in comments/javadoc). Writes UTF8 without BOM.

$root = 'd:\IdeaProject\Automation_WebUI_Framework_For_DBB'
$targets = @(
    (Join-Path $root 'test-automation\src\test\java\com\hsbc\cmb\hk\dbb\automation\framework\web\lifecycle'),
    (Join-Path $root 'web\src\test\java\com\hsbc\cmb\hk\dbb\automation\framework\web\lifecycle')
)
$enc = New-Object System.Text.UTF8Encoding($false)

foreach ($dir in $targets) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Recurse -Filter *.java | ForEach-Object {
        $path = $_.FullName
        $text = [System.IO.File]::ReadAllText($path)
        $lines = $text -split "`r?`n"
        $imports = $lines | Where-Object { $_ -match '^\s*import com\.hsbc\..*\.lifecycle\.(browser|context|page|scenario|serenity|bootstrap|media)\.' }
        if (-not $imports) { return }

        # code-only text: strip import lines and comment-only lines
        $codeOnly = $lines | Where-Object {
            $t = $_.Trim()
            -not ($t -match '^import ') -and
            -not ($t -match '^//') -and
            -not ($t -match '^/\*') -and
            -not ($t -match '^\*')
        }
        $codeText = $codeOnly -join "`n"

        $toRemove = New-Object System.Collections.ArrayList
        foreach ($imp in $imports) {
            $cls = ($imp -replace '^\s*import\s+', '') -replace ';\s*$', ''
            $simple = $cls.Substring($cls.LastIndexOf('.') + 1)
            # -cmatch: case sensitive, so field names like browserRegistry do not satisfy BrowserRegistry
            $usedInCode = $codeText -cmatch ('\b' + [regex]::Escape($simple) + '\b')
            # keep imports referenced from javadoc {@link ...} as well
            $usedInJavadoc = $text -cmatch ('\{@link\s+#?' + [regex]::Escape($simple) + '\b')
            if (-not $usedInCode -and -not $usedInJavadoc) {
                [void]$toRemove.Add($imp)
            }
        }
        if ($toRemove.Count -gt 0) {
            $kept = $lines | Where-Object { $toRemove -notcontains $_ }
            [System.IO.File]::WriteAllText($path, ($kept -join "`r`n"), $enc)
            Write-Output ('PRUNED ' + $_.Name + ' removed=' + $toRemove.Count)
            foreach ($r in $toRemove) { Write-Output ('    - ' + $r.Trim()) }
        }
    }
}
