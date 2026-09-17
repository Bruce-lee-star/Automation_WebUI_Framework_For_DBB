$root = "d:/IdeaProject/Automation_WebUI_Framework_For_DBB"
$life = Join-Path $root "web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle"
$subs = @("browser","context","page","scenario","serenity","bootstrap","media")
$publicize = @{
  "BrowserRegistryImpl.java"=$true;"BrowserStartupImpl.java"=$true;"BrowserRestartImpl.java"=$true;"BrowserCleanupImpl.java"=$true;
  "ContextRegistryImpl.java"=$true;"PageRegistryImpl.java"=$true;
  "ScenarioLifecycle.java"=$true;"PlaywrightSerenityBridge.java"=$true;"SerenityBusBridge.java"=$true;"TestContextBridge.java"=$true;
  "PlaywrightInitializer.java"=$true;"PlaywrightContextManager.java"=$true;"PlaywrightScreenshotManager.java"=$true;
}
$enc = New-Object System.Text.UTF8Encoding($false)

foreach ($sub in $subs) {
    $dir = Join-Path $life $sub
    if (-not (Test-Path $dir)) { continue }
    foreach ($f in Get-ChildItem -Path $dir -Filter *.java) {
        $name = $f.Name
        $text = [System.IO.File]::ReadAllText($f.FullName)
        # force package to match subdir
        $text = $text -replace 'package com\.hsbc\.cmb\.hk\.dbb\.automation\.framework\.web\.lifecycle(\.\w+)?;', "package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.$sub;"
        # idempotent publicize
        if ($publicize.ContainsKey($name)) {
            $text = $text -replace '(?m)^(final class )', 'public final class '
            $text = $text -replace '(?m)^(class )', 'public class '
        }
        [System.IO.File]::WriteAllText($f.FullName, $text, $enc)
        Write-Host "normalized $sub/$name"
    }
}
Write-Host "FIXDONE"
