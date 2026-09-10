$ErrorActionPreference = 'Stop'
$root = 'd:/IdeaProject/Automation_WebUI_Framework_For_DBB'
$RUNTIME_IMPORT = 'import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;'
$enc = New-Object System.Text.UTF8Encoding($false)

# file -> array of (method, role)  -- plain @(...) arrays, NO leading comma
$map = @{
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/session/SessionManager.java' = @(
    @('hasContext','contextRegistry'), @('discardCurrentContext','contextRegistry')
  )
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/concurrent/ConcurrentScenarioExecutor.java' = @(
    @('setConfigId','browserRegistry'), @('sharedConfigId','browserRegistry')
  )
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/base/PageContextState.java' = @(
    @('setPage','pageRegistry')
  )
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/listener/PlaywrightListener.java' = @(
    @('closePage','pageRegistry'), @('closeContext','contextRegistry')
  )
  'web/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/lifecycle/PlaywrightSerenityBridge.java' = @(
    @('closePage','pageRegistry'), @('closeContext','contextRegistry'), @('setPage','pageRegistry'), @('ensureConfigId','browserRegistry')
  )
}

foreach ($rel in $map.Keys) {
  $f = Join-Path $root $rel
  $txt = [System.IO.File]::ReadAllText($f)
  $orig = $txt
  $isLifecycle = $rel -like '*web/lifecycle/*'
  foreach ($p in $map[$rel]) {
    $method = $p[0]; $role = $p[1]
    $from = "PlaywrightManager.$method("
    $to = "PlaywrightRuntime.instance().$role.$method("
    $txt = $txt.Replace($from, $to)
  }
  $changed = ($txt -ne $orig)
  if ($changed -and -not $isLifecycle) {
    if ($txt -notmatch [regex]::Escape($RUNTIME_IMPORT)) {
      $idx = $txt.IndexOf('package ')
      $nl = $txt.IndexOf([Environment]::NewLine, $idx)
      if ($nl -lt 0) { $nl = $txt.IndexOf("`n", $idx) }
      $txt = $txt.Insert($nl + 1, "$RUNTIME_IMPORT`r`n")
    }
  }
  if ($txt -notmatch 'PlaywrightManager\.') {
    $txt = [regex]::Replace($txt, '(?m)^\s*import [^;]*\.PlaywrightManager;\r?\n', '')
  }
  if ($txt -ne $orig) {
    [System.IO.File]::WriteAllText($f, $txt, $enc)
    Write-Host "UPDATED: $rel"
  } else {
    Write-Host "NOCHANGE: $rel"
  }
}
Write-Host "MIGRATE DONE"
