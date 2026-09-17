$dir = "web"
Get-ChildItem -Path $dir -Recurse -Filter *.java | ForEach-Object {
    $p = $_.FullName
    $bytes = [System.IO.File]::ReadAllBytes($p)
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $new = [System.Text.RegularExpressions.Regex]::Replace($text, 'FrameworkConfig(?!Manager)', 'WebFrameworkConfig')
    if ($new -ne $text) {
        [System.IO.File]::WriteAllText($p, $new, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ("updated: " + $p)
    }
}
