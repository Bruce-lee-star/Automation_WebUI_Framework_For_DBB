<#
.SYNOPSIS
    JUnit4 -> JUnit5 断言参数顺序修正（仅处理 message-first 形式，基于干净 JUnit4 源码）。
.DESCRIPTION
    字节级替换，UTF8 无 BOM。解析 assertX(...) 调用（括号/字符串感知），
    将 JUnit4 的 message-first 形式旋转为 JUnit5 的 message-last 形式：
      assertTrue("msg", cond)            -> assertTrue(cond, "msg")
      assertFalse("msg", cond)           -> assertFalse(cond, "msg")
      assertNull("msg", obj)             -> assertNull(obj, "msg")
      assertNotNull("msg", obj)          -> assertNotNull(obj, "msg")
      assertEquals("msg", expected, actual)   -> assertEquals(expected, actual, "msg")
      assertSame/assertNotSame/assertArrayEquals 同理
    旋转仅在「首参是字符串字面量」且参数个数匹配时触发：
      assertTrue/False/Null/NotNull 仅 2 参；
      assertEquals/Same/NotSame/ArrayEquals 仅 3 参（2 参且首参为字符串视为 expected/actual，合法 JUnit5，不旋转）。
    ⚠ 必须作用于「干净 JUnit4」源（推荐先 git checkout HEAD 还原再跑 migrate_junit4_to_junit5.ps1），
      否则已损坏（message 失引号）的源码无法被本脚本识别。
.PARAMETER Apply
    实际写入文件；缺省为预览（打印每个将被旋转的调用）。
#>
[CmdletBinding()]
param([switch]$Apply, [switch]$BoolOnly, [string[]]$Path)

$ErrorActionPreference = 'Stop'

if ($Path -and $Path.Count -gt 0) {
    $files = $Path | Where-Object { Test-Path $_ } | ForEach-Object { Get-Item $_ }
} else {
    $roots = @(
        'test-automation/src/test/java',
        'web/src/test/java',
        'reporting/src/test/java',
        'route/src/test/java'
    )
}

$assertNames = @('assertTrue','assertFalse','assertNull','assertNotNull','assertEquals','assertSame','assertNotSame','assertArrayEquals')
$msgFirstTwo   = @('assertTrue','assertFalse','assertNull','assertNotNull')
$msgFirstThree = @('assertEquals','assertSame','assertNotSame','assertArrayEquals')

function Find-MatchingParen {
    param([string]$txt, [int]$openIdx)
    $depth = 0; $inStr = $false; $inChar = $false; $esc = $false
    $i = $openIdx
    while ($i -lt $txt.Length) {
        $c = $txt[$i]
        if ($inStr) {
            if ($esc) { $esc = $false }
            elseif ($c -eq '\') { $esc = $true }
            elseif ($c -eq '"') { $inStr = $false }
        } elseif ($inChar) {
            if ($esc) { $esc = $false }
            elseif ($c -eq '\') { $esc = $true }
            elseif ($c -eq "'") { $inChar = $false }
        } else {
            if ($c -eq '"') { $inStr = $true }
            elseif ($c -eq "'") { $inChar = $true }
            elseif ($c -eq '(') { $depth++ }
            elseif ($c -eq ')') {
                $depth--
                if ($depth -eq 0) { return $i }
            }
        }
        $i++
    }
    return -1
}

function Split-TopLevelArgs {
    param([string]$inner)
    $args = New-Object System.Collections.Generic.List[string]
    $cur = New-Object System.Text.StringBuilder
    $depth = 0; $inStr = $false; $inChar = $false; $esc = $false
    foreach ($c in $inner.ToCharArray()) {
        if ($inStr) {
            $cur.Append($c) > $null
            if ($esc) { $esc = $false }
            elseif ($c -eq '\') { $esc = $true }
            elseif ($c -eq '"') { $inStr = $false }
        } elseif ($inChar) {
            $cur.Append($c) > $null
            if ($esc) { $esc = $false }
            elseif ($c -eq '\') { $esc = $true }
            elseif ($c -eq "'") { $inChar = $false }
        } else {
            if ($c -eq '"') { $inStr = $true; $cur.Append($c) > $null }
            elseif ($c -eq "'") { $inChar = $true; $cur.Append($c) > $null }
            elseif ($c -eq '(' -or $c -eq '[' -or $c -eq '{') { $depth++; $cur.Append($c) > $null }
            elseif ($c -eq ')' -or $c -eq ']' -or $c -eq '}') { $depth--; $cur.Append($c) > $null }
            elseif ($c -eq ',' -and $depth -eq 0) {
                $args.Add($cur.ToString()); $cur.Clear() > $null
            } else { $cur.Append($c) > $null }
        }
    }
    if ($cur.Length -gt 0) { $args.Add($cur.ToString()) }
    return $args
}

function Convert-Assertions {
    param([string]$txt)
    $out = New-Object System.Text.StringBuilder
    $i = 0; $len = $txt.Length; $modified = $false
    while ($i -lt $len) {
        $hit = $false
        foreach ($name in $assertNames) {
            if ($i + $name.Length -le $len -and $txt.Substring($i, $name.Length) -eq $name) {
                $prevOk = ($i -eq 0) -or (-not [char]::IsLetterOrDigit($txt[$i-1]) -and $txt[$i-1] -ne '_')
                $paren = $i + $name.Length
                if ($prevOk -and $paren -lt $len -and $txt[$paren] -eq '(') {
                    $close = Find-MatchingParen $txt $paren
                    if ($close -ge 0) {
                        $inner = $txt.Substring($paren+1, $close-$paren-1)
                        $args = Split-TopLevelArgs $inner
                        $needRotate = $false
                        if ($args.Count -ge 2) {
                            $first = $args[0].TrimStart()
                            $startsStr = $first.StartsWith('"')
                            if ($name -in $msgFirstTwo -and $args.Count -eq 2 -and $startsStr) { $needRotate = $true }
                            elseif ((-not $BoolOnly) -and $name -in $msgFirstThree -and $args.Count -eq 3 -and $startsStr) { $needRotate = $true }
                        }
                        if ($needRotate) {
                            $rotated = ($args[1..($args.Count-1)] + $args[0]) -join ', '
                            $call = $name + '(' + $rotated + ')'
                            if (-not $Apply) {
                                Write-Host ("  [rotate] {0} -> {1}" -f ($name + '(' + $inner.Trim() + ')'), $call)
                            }
                            $out.Append($call) > $null
                            $i = $close + 1
                            $hit = $true; $modified = $true
                            break
                        } else {
                            # copy verbatim (this assert call unchanged)
                            $out.Append($txt.Substring($i, $close-$i+1)) > $null
                            $i = $close + 1
                            $hit = $true
                            break
                        }
                    }
                }
            }
        }
        if (-not $hit) { $out.Append($txt[$i]) > $null; $i++ }
    }
    return @{ text = $out.ToString(); modified = $modified }
}

$total = 0
if ($Path -and $Path.Count -gt 0) {
    $files = $files | Where-Object { $_.Extension -eq '.java' }
    foreach ($f in $files) {
        $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
        $txt = [System.Text.Encoding]::UTF8.GetString($bytes)
        $r = Convert-Assertions $txt
        if ($r.modified) {
            $total++
            if ($Apply) {
                [System.IO.File]::WriteAllBytes($f.FullName, [System.Text.Encoding]::UTF8.GetBytes($r.text))
                Write-Host ("  [apply] {0}" -f $f.FullName)
            } else {
                Write-Host ("== [preview] {0}" -f $f.FullName)
            }
        }
    }
} else {
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { Write-Host "SKIP (missing): $root"; continue }
        foreach ($f in Get-ChildItem -Path $root -Recurse -Filter *.java) {
            $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
            $txt = [System.Text.Encoding]::UTF8.GetString($bytes)
            $r = Convert-Assertions $txt
            if ($r.modified) {
                $total++
                if ($Apply) {
                    [System.IO.File]::WriteAllBytes($f.FullName, [System.Text.Encoding]::UTF8.GetBytes($r.text))
                    Write-Host ("  [apply] {0}" -f $f.FullName)
                } else {
                    Write-Host ("== [preview] {0}" -f $f.FullName)
                }
            }
        }
    }
}
$mode = if ($Apply) { 'APPLIED' } else { 'DRY-RUN' }
Write-Host ("`n旋转文件数: {0} ({1})" -f $total, $mode)
