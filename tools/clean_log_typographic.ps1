$ErrorActionPreference = 'Stop'

# Root of the framework workspace
$root = 'd:/IdeaProject/Automation_WebUI_Framework_For_DBB'

# Western typographic punctuation -> ASCII equivalents.
# These code points have NO mapping in GBK(CP936), so they always render as '?'
# on a GBK terminal. CJK characters are intentionally NOT included (GBK covers them).
$map = @{
    ([char]0x2014) = '-'   # em dash
    ([char]0x2013) = '-'   # en dash
    ([char]0x2018) = ([char]0x27)  # left single quote
    ([char]0x2019) = ([char]0x27)  # right single quote
    ([char]0x201C) = ([char]0x22)  # left double quote
    ([char]0x201D) = ([char]0x22)  # right double quote
    ([char]0x201E) = ([char]0x22)  # double low quote
    ([char]0x2192) = '->' # rightwards arrow
    ([char]0x21D2) = '=>' # rightwards double arrow
    ([char]0x2026) = '...' # horizontal ellipsis
    ([char]0x2022) = '*'  # bullet
}

# Identify a logging call line: <log|logger|verbose...>.<level>(
$logRe = [regex]'(?i)(?:log|logger|verbose)\w*\.(?:info|debug|warn|error|trace|fatal|infoifverbose|debugifverbose|traceifverbose)\s*\('

# Only touch source files under any module's src/ (never target/ build output)
$files = Get-ChildItem -Path $root -Recurse -Include '*.java' |
    Where-Object { $_.FullName -match '[\\/]src[\\/]' -and $_.FullName -notmatch '[\\/]target[\\/]' }

$totalFiles = 0
$totalLines = 0

foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)

    $evaluator = [System.Text.RegularExpressions.MatchEvaluator] {
        param($m)
        $line = $m.Value
        if ($line -cmatch $logRe) {
            $sb = New-Object System.Text.StringBuilder
            foreach ($ch in $line.ToCharArray()) {
                if ($map.ContainsKey($ch)) { $null = $sb.Append($map[$ch]) } else { $null = $sb.Append($ch) }
            }
            $sb.ToString()
        } else {
            $line
        }
    }

    # (?m)^.*$ matches each line (excluding line terminators); terminators are preserved,
    # so original CRLF/LF line endings are never altered.
    $newTxt = [System.Text.RegularExpressions.Regex]::Replace($txt, '(?m)^.*$', $evaluator)

    if ($newTxt -ne $txt) {
        [System.IO.File]::WriteAllText($f.FullName, $newTxt, (New-Object System.Text.UTF8Encoding($false)))
        $totalFiles++
        # count changed lines for reporting
        $pairs = @()
        $a = $txt -split "`n"
        $b = $newTxt -split "`n"
        for ($i = 0; $i -lt $a.Length; $i++) {
            if ($a[$i] -ne $b[$i]) { $totalLines++ }
        }
    }
}

Write-Host "Updated $totalFiles file(s), $totalLines line(s)"
