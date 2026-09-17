$root = "d:/IdeaProject/Automation_WebUI_Framework_For_DBB"
$life = Join-Path $root "web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle"
$base = "com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle"
$types = @(
  "PlaywrightManager","PlaywrightRuntime","PlaywrightRuntimeState","DefaultRuntimeProvider",
  "ConcurrentContextExecutor","ConcurrentContextOptions","ContextTask","ContextTaskResult",
  "PageEventMonitor","PlaywrightConfigManager","ProxyConfigResolver",
  "BrowserRegistry","BrowserRegistryImpl","BrowserStartup","BrowserStartupImpl",
  "BrowserRestart","BrowserRestartImpl","BrowserCleanup","BrowserCleanupImpl","BrowserCrashGuard",
  "ContextRegistry","ContextRegistryImpl","CustomOptions","CustomOptionsManager",
  "PageRegistry","PageRegistryImpl",
  "ScenarioLifecycle",
  "PlaywrightSerenityBridge","SerenityBusBridge","TestContextBridge",
  "PlaywrightContextManager","PlaywrightInitializer",
  "PlaywrightScreenshotManager"
)
$subOf = @{
  BrowserRegistry='browser';BrowserRegistryImpl='browser';BrowserStartup='browser';BrowserStartupImpl='browser';
  BrowserRestart='browser';BrowserRestartImpl='browser';BrowserCleanup='browser';BrowserCleanupImpl='browser';BrowserCrashGuard='browser';
  ContextRegistry='context';ContextRegistryImpl='context';CustomOptions='context';CustomOptionsManager='context';
  PageRegistry='page';PageRegistryImpl='page';
  ScenarioLifecycle='scenario';
  PlaywrightSerenityBridge='serenity';SerenityBusBridge='serenity';TestContextBridge='serenity';
  PlaywrightContextManager='bootstrap';PlaywrightInitializer='bootstrap';
  PlaywrightScreenshotManager='media'
}
$enc = New-Object System.Text.UTF8Encoding($false)

function Fqcn($t){ if ($subOf.ContainsKey($t)) { return "$base.$($subOf[$t]).$t" } else { return "$base.$t" } }

foreach ($f in Get-ChildItem -Path $life -Recurse -Filter *.java) {
    $text = [System.IO.File]::ReadAllText($f.FullName)
    $declNames = @()
    foreach ($m in [regex]::Matches($text, '(?m)^(?:(?:public|final|abstract|static)\s+)*(?:class|interface|enum)\s+([A-Z]\w+)')) { $declNames += $m.Groups[1].Value }
    # remove parent-level lifecycle imports (now broken/redundant; will re-add)
    $text = [regex]::Replace($text, '(?m)^import com\.hsbc\.cmb\.hk\.dbb\.automation\.framework\.web\.lifecycle\.[A-Z]\w+;\r?\n', '')
    # recompute existing imports after removal
    $existing = [regex]::Matches($text, '(?m)^import [^;]+;') | ForEach-Object { $_.Value.Trim() }
    $toAdd = @()
    foreach ($t in $types) {
        $impLine = "import $(Fqcn $t);"
        if ($existing -contains $impLine) { continue }
        if ($declNames -contains $t) { continue }
        $toAdd += $impLine
    }
    if ($toAdd.Count -gt 0) {
        $addBlock = ($toAdd -join "`r`n") + "`r`n"
        $text = [regex]::Replace($text, '(?m)^(package [^;]+;\r?\n)', { param($m) $m.Groups[0].Value + $addBlock }, 1)
    }
    [System.IO.File]::WriteAllText($f.FullName, $text, $enc)
    Write-Host "fixed $($f.FullName.Replace($life,'')) added=$($toAdd.Count)"
}
Write-Host "IMPORTSDONE"
