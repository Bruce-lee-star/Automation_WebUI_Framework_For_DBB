$ErrorActionPreference = 'Stop'
$root = 'd:/IdeaProject/Automation_WebUI_Framework_For_DBB'
$files = @(
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/BrowserRegistry.java',
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/ContextRegistry.java',
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/PageRegistry.java',
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/BrowserRegistryImpl.java',
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/ContextRegistryImpl.java',
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/PageRegistryImpl.java',
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/DefaultRuntimeProvider.java',
  'test-automation/src/test/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/PlaywrightRuntimePolymorphismTest.java',
  'test-automation/src/test/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/BrowserRegistryConfigIdValidationTest.java'
)
$pairs = @(
  ,@('realGetPlaywright','getPlaywright')
  ,@('realGetBrowser','getBrowser')
  ,@('realGetContext','getContext')
  ,@('realGetPage','getPage')
)
$enc = New-Object System.Text.UTF8Encoding($false)
foreach ($rel in $files) {
  $f = Join-Path $root $rel
  if (-not (Test-Path $f)) { Write-Host "SKIP missing: $rel"; continue }
  $txt = [System.IO.File]::ReadAllText($f)
  $orig = $txt
  foreach ($p in $pairs) { $txt = $txt.Replace($p[0], $p[1]) }
  if ($txt -ne $orig) {
    [System.IO.File]::WriteAllText($f, $txt, $enc)
    Write-Host "UPDATED: $rel"
  } else {
    Write-Host "NOCHANGE: $rel"
  }
}
Write-Host "RENAME DONE"
