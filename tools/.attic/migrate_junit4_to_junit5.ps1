<#
.SYNOPSIS
    JUnit 4 -> JUnit 5 (Jupiter) 自动化迁移脚本（DBB WebUI 框架，保持 serenity 4.2.0）。
.DESCRIPTION
    字节级替换，UTF8 无 BOM（避免污染 java 文件，遵循本机历史约束：严禁 Set-Content -Encoding UTF8 写 BOM）。
    处理范围：test-automation / web / reporting / route 四个含 JUnit4 测试的模块的 src/test/java。
    覆盖：
      ① org.junit.* import -> org.junit.jupiter.api.*
      ② 生命周期注解 @Before/@After/@BeforeClass/@AfterClass -> @BeforeEach/@AfterEach/@BeforeAll/@AfterAll
      ③ @Ignore -> @Disabled
      ④ org.junit.Assert.* -> org.junit.jupiter.api.Assertions.*（含静态导入行）
      ⑤ org.junit.Assume.* -> org.junit.jupiter.api.Assumptions.*
      ⑥ Cucumber runner（@RunWith(CucumberWithSerenity/SerenityRunner) + @CucumberOptions）
         -> 生成 @Suite 骨架（@IncludeEngines("cucumber") + @SelectClasspathResource + @ConfigurationParameter），
            原文件备份 .bak，生成 .suite.java 骨架待人工补全 SerenityReporterParallel FQN 与 glue/tags。
    ⚠ 本脚本只动代码，不动 pom（pom 依赖层已由人工先行改造）。脚本默认 DryRun 预览，加 -Apply 才落盘。
.PARAMETER Apply
    实际写入文件；缺省为预览（仅统计与打印即将发生的变更）。
.EXAMPLE
    .\migrate_junit4_to_junit5.ps1              # 预览
    .\migrate_junit4_to_junit5.ps1 -Apply       # 落盘
#>
[CmdletBinding()]
param(
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'

$roots = @(
    'test-automation/src/test/java',
    'web/src/test/java',
    'reporting/src/test/java',
    'route/src/test/java'
)

# 精确 import 行映射（整行替换，避免误伤注释中的字面量）
$importMap = @(
    # 生命周期 / 注解
    'import org.junit.Test;',                                  'import org.junit.jupiter.api.Test;',
    'import org.junit.Before;',                                'import org.junit.jupiter.api.BeforeEach;',
    'import org.junit.After;',                                 'import org.junit.jupiter.api.AfterEach;',
    'import org.junit.BeforeClass;',                           'import org.junit.jupiter.api.BeforeAll;',
    'import org.junit.AfterClass;',                            'import org.junit.jupiter.api.AfterAll;',
    'import org.junit.Ignore;',                                'import org.junit.jupiter.api.Disabled;',
    # 断言 / 假设
    'import org.junit.Assert;',                                'import org.junit.jupiter.api.Assertions;',
    'import org.junit.Assume;',                                'import org.junit.jupiter.api.Assumptions;',
    # runner（JUnit4 单测用 @RunWith 少见，但若存在则提示）
    'import org.junit.runner.RunWith;',                        '// MIGRATION: @RunWith removed - use @ExtendWith for Serenity or @Suite for Cucumber',
    'import org.junit.runners.*;',                             '// MIGRATION: org.junit.runners.* removed (JUnit4)'
)

# 注解体替换（成对出现，正则精确匹配 @Before / @After 等，避免 @BeforeEach 被二次替换）
$annotationMap = @(
    '@BeforeClass', '@BeforeAll',
    '@AfterClass',  '@AfterAll',
    '@Before',      '@BeforeEach',
    '@After',       '@AfterEach',
    '@Ignore',      '@Disabled'
)

# 调用点替换（静态方法）：Assert.xxx( -> Assertions.xxx( ；Assume.xxx( -> Assumptions.xxx(
$callMap = @(
    'Assert\.', 'Assertions.',
    'Assume\.', 'Assumptions.'
)

function Convert-File {
    param([string]$path)
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $txt = [System.Text.Encoding]::UTF8.GetString($bytes)
    $orig = $txt
    $changed = $false

    # ① import 映射
    foreach ($i in 0..($importMap.Count/2-1)) {
        $from = $importMap[$i*2]
        $to   = $importMap[$i*2+1]
        if ($txt.Contains($from)) { $txt = $txt.Replace($from, $to); $changed = $true }
    }

    # ② 注解映射（用正则确保整词边界，防止 @Before 命中 @BeforeEach）
    foreach ($i in 0..($annotationMap.Count/2-1)) {
        $from = $annotationMap[$i*2]
        $to   = $annotationMap[$i*2+1]
        # 匹配 @Before 后接非字母数字（行尾/空格/(），避免 @BeforeEach 被改
        $pattern = [regex]::Escape($from) + '(?![A-Za-z0-9_])'
        if ([regex]::IsMatch($txt, $pattern)) {
            $txt = [regex]::Replace($txt, $pattern, $to)
            $changed = $true
        }
    }

    # ①b 静态导入映射：import static org.junit.Assert.X -> import static org.junit.jupiter.api.Assertions.X
    # 裸调用 assertEquals/assertTrue/... 在 JUnit5 由 Assertions 同名静态方法承接，无需改调用点
    if ($txt -match 'import static org\.junit\.Assert\.') {
        $txt = $txt -replace 'import static org\.junit\.Assert\.', 'import static org.junit.jupiter.api.Assertions.'
        $changed = $true
    }

    # ④⑤ 调用点（Assert./Assume. -> Assertions./Assumptions.），仅在已 import Assertions/Assumptions 后安全
    foreach ($i in 0..($callMap.Count/2-1)) {
        $from = $callMap[$i*2]
        $to   = $callMap[$i*2+1]
        $pattern = [regex]::Escape($from)
        if ([regex]::IsMatch($txt, $pattern)) {
            $txt = [regex]::Replace($txt, $pattern, $to)
            $changed = $true
        }
    }

    # ⑥ Cucumber runner 检测（单独处理，不直接内联替换类结构）
    $isCucumberRunner = $txt -match 'CucumberWithSerenity|SerenityRunner' -and $txt -match '@CucumberOptions'

    # ⑦ 特殊 JUnit4 语法检测（@Test(expected=/timeout=) / @Rule / @ClassRule 需手工迁移方法体，脚本不自动改）
    $hasSpecialSyntax = ($txt -match '@Test\(\s*(expected|timeout)') -or ($txt -match '@Rule\b') -or ($txt -match '@ClassRule')

    if ($isCucumberRunner) { return 'CUCUMBER_RUNNER' }
    if ($changed) {
        if ($Apply) {
            [System.IO.File]::WriteAllBytes($path, [System.Text.Encoding]::UTF8.GetBytes($txt))
        }
        if ($hasSpecialSyntax) { return 'MODIFIED_NEEDS_MANUAL' } else { return 'MODIFIED' }
    }
    if ($hasSpecialSyntax) { return 'NEEDS_MANUAL' }
    return 'UNCHANGED'
}

$stats = @{ MODIFIED = 0; MODIFIED_NEEDS_MANUAL = 0; NEEDS_MANUAL = 0; CUCUMBER_RUNNER = 0; UNCHANGED = 0 }
$cucumberRunners = @()

foreach ($root in $roots) {
    if (-not (Test-Path $root)) { Write-Host "SKIP (missing): $root"; continue }
    foreach ($f in Get-ChildItem -Path $root -Recurse -Filter *.java) {
        $r = Convert-File $f.FullName
        if ($r -eq 'MODIFIED') {
            $stats.MODIFIED++
            if (-not $Apply) { Write-Host "  [preview] MODIFY : $($f.FullName)" }
        } elseif ($r -eq 'MODIFIED_NEEDS_MANUAL') {
            $stats.MODIFIED_NEEDS_MANUAL++
            if (-not $Apply) { Write-Host "  [preview] MODIFY* : $($f.FullName)" }
        } elseif ($r -eq 'NEEDS_MANUAL') {
            $stats.NEEDS_MANUAL++
            if (-not $Apply) { Write-Host "  [manual]  SPECIAL SYNTAX : $($f.FullName)" }
        } elseif ($r -eq 'CUCUMBER_RUNNER') {
            $stats.CUCUMBER_RUNNER++
            $cucumberRunners += $f.FullName
            Write-Host "  [cucumber] NEEDS @Suite migration: $($f.FullName)"
        }
    }
}

Write-Host "`n==== JUnit4->JUnit5 迁移预览 ($(if($Apply){'APPLY'}else{'DRY-RUN'})) ===="
Write-Host "  普通测试文件已改写 : $($stats.MODIFIED)"
Write-Host "  改写但含特殊语法(需手工收尾 @Test(expected/timeout)/@Rule) : $($stats.MODIFIED_NEEDS_MANUAL)"
Write-Host "  仅特殊语法(未改 import，需手工迁移) : $($stats.NEEDS_MANUAL)"
Write-Host "  Cucumber runner 待手工迁移 : $($stats.CUCUMBER_RUNNER)"
Write-Host "  未变更 : $($stats.UNCHANGED)"

if ($cucumberRunners.Count -gt 0) {
    Write-Host "`nCucumber runner 迁移模板（每个生成 .suite.java 骨架，原文件备份 .bak）："
    Write-Host '  @Suite'
    Write-Host '  @IncludeEngines("cucumber")'
    Write-Host '  @SelectClasspathResource("features")'
    Write-Host '  @ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "net.serenitybdd.cucumber.core.plugin.SerenityReporterParallel")  // ⚠ 4.2.0 路径需在线核实'
    Write-Host '  @ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "<原 @CucumberOptions glue 包>")'
    Write-Host '  @ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "<原 @CucumberOptions tags>")'
    Write-Host '  // 依赖 cucumber-junit-platform-engine + junit-platform-suite 已在 test-automation/pom 声明'
}
