<#
.SYNOPSIS  JUnit4 -> JUnit5 断言参数顺序反转修复（确定性迁移补全）
.DESCRIPTION
  JUnit5 将 message 参数统一移到末尾：
    JUnit4: assertTrue(String msg, boolean cond) / assertEquals(String msg, Object exp, Object act)
    JUnit5: assertTrue(boolean cond, String msg) / assertEquals(Object exp, Object act, String msg)
  迁移脚本 migrate_junit4_to_junit5.ps1 只替换了 import，遗漏了参数顺序反转。
  本脚本批量修复全仓 *Test.java 中以下模式（message 为字符串字面量时）：
    4-参 assertEquals(String, exp, act, delta) -> assertEquals(exp, act, delta, msg)
    3-参 assertEquals/assertSame/assertNotSame/assertArrayEquals(String, exp, act) -> (exp, act, msg)
    2-参 assertTrue/assertFalse/assertNull/assertNotNull(String, X) -> (X, msg)
  字节级 UTF8 无 BOM 写回。默认 preview（仅打印待改文件）；-Apply 落盘。
#>
param([switch]$Apply)

$rules = @(
    # 4-param assertEquals(String, exp, act, delta) -> assertEquals(exp, act, delta, msg)
    'assertEquals\(\s*"([^"]*)"\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,\s*([^)]+?)\)', 'assertEquals($2, $3, $4, "$1")'
    # 3-param (String, exp, act)
    '(assertEquals|assertSame|assertNotSame|assertArrayEquals)\(\s*"([^"]*)"\s*,\s*([^,]+?)\s*,\s*([^)]+?)\)', '$1($2, $3, "$4")'
    # 2-param (String, X)
    '(assertTrue|assertFalse|assertNull|assertNotNull)\(\s*"([^"]*)"\s*,\s*([^)]*?)\)', '$1($3, "$2")'
)

$opt = [System.Text.RegularExpressions.RegexOptions]::Singleline
$enc = [System.Text.UTF8Encoding]::new($false)
$count = 0
Get-ChildItem -Path . -Recurse -Include *Test.java -File | ForEach-Object {
    $f = $_.FullName
    $txt = [System.IO.File]::ReadAllText($f)
    $orig = $txt
    for ($i = 0; $i -lt $rules.Count; $i += 2) {
        $txt = [System.Text.RegularExpressions.Regex]::Replace($txt, $rules[$i], $rules[$i + 1], $opt)
    }
    if ($txt -ne $orig) {
        $count++
        if ($Apply) {
            [System.IO.File]::WriteAllBytes($f, $enc.GetBytes($txt))
            Write-Host "  [apply] MODIFY : $f"
        } else {
            Write-Host "  [preview] MODIFY : $f"
        }
    }
}
Write-Host "待修改文件数: $count"
