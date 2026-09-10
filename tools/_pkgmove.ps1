$root = "d:/IdeaProject/Automation_WebUI_Framework_For_DBB"
$life = Join-Path $root "web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle"

$moves = @{
  "BrowserRegistry.java"="browser"; "BrowserRegistryImpl.java"="browser";
  "BrowserStartup.java"="browser"; "BrowserStartupImpl.java"="browser";
  "BrowserRestart.java"="browser"; "BrowserRestartImpl.java"="browser";
  "BrowserCleanup.java"="browser"; "BrowserCleanupImpl.java"="browser";
  "BrowserCrashGuard.java"="browser";
  "ContextRegistry.java"="context"; "ContextRegistryImpl.java"="context";
  "CustomOptions.java"="context"; "CustomOptionsManager.java"="context";
  "PageRegistry.java"="page"; "PageRegistryImpl.java"="page";
  "ScenarioLifecycle.java"="scenario";
  "PlaywrightSerenityBridge.java"="serenity"; "SerenityBusBridge.java"="serenity"; "TestContextBridge.java"="serenity";
  "PlaywrightInitializer.java"="bootstrap"; "PlaywrightContextManager.java"="bootstrap";
  "PlaywrightScreenshotManager.java"="media";
}
$publicize = @{
  "BrowserRegistryImpl.java"=$true;"BrowserStartupImpl.java"=$true;"BrowserRestartImpl.java"=$true;"BrowserCleanupImpl.java"=$true;
  "ContextRegistryImpl.java"=$true;"PageRegistryImpl.java"=$true;
  "ScenarioLifecycle.java"=$true;"PlaywrightSerenityBridge.java"=$true;"SerenityBusBridge.java"=$true;"TestContextBridge.java"=$true;
  "PlaywrightInitializer.java"=$true;"PlaywrightContextManager.java"=$true;"PlaywrightScreenshotManager.java"=$true;
}
$inplacePublic = @("PlaywrightRuntimeState.java")

$enc = New-Object System.Text.UTF8Encoding($false)

# clean any prior partial subdirs
foreach ($sub in @("browser","context","page","scenario","serenity","bootstrap","media")) {
    $dir = Join-Path $life $sub
    if (Test-Path $dir) { Remove-Item -Path $dir -Recurse -Force }
}
# recreate
foreach ($sub in @("browser","context","page","scenario","serenity","bootstrap","media")) {
    $dir = Join-Path $life $sub
    New-Item -ItemType Directory -Path $dir | Out-Null
}

foreach ($file in $moves.Keys) {
    $sub = $moves[$file]
    $src = Join-Path $life $file
    $dst = Join-Path $life (Join-Path $sub $file)
    Move-Item -Path $src -Destination $dst -Force
    $text = [System.IO.File]::ReadAllText($dst)
    $text = $text -replace 'package com\.hsbc\.cmb\.hk\.dbb\.automation\.framework\.web\.lifecycle;', "package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.$sub;"
    if ($publicize.ContainsKey($file)) {
        $text = $text -replace '(?m)^(final class )', 'public final class '
        $text = $text -replace '(?m)^(class )', 'public class '
    }
    [System.IO.File]::WriteAllText($dst, $text, $enc)
    Write-Host "moved $file -> $sub"
}

foreach ($file in $inplacePublic) {
    $src = Join-Path $life $file
    $text = [System.IO.File]::ReadAllText($src)
    $text = $text -replace '(?m)^(final class )', 'public final class '
    [System.IO.File]::WriteAllText($src, $text, $enc)
    Write-Host "publicized $file"
}
Write-Host "DONE"
