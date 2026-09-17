# doc16 Phase 2: routes PlaywrightManager.STATE.<field>.<op>(...) to the LifecycleState
# role interface via the composition root: PlaywrightRuntime.instance().state.<op>(...)
# Pure ASCII, writes UTF8 without BOM.

$root = 'd:\IdeaProject\Automation_WebUI_Framework_For_DBB'
$enc = New-Object System.Text.UTF8Encoding($false)

$pairs = @(
    @('PlaywrightManager.STATE.playwrightInstances.containsKey(', 'PlaywrightRuntime.instance().state.hasPlaywright('),
    @('PlaywrightManager.STATE.playwrightInstances.entrySet()', 'PlaywrightRuntime.instance().state.playwrightEntries()'),
    @('PlaywrightManager.STATE.playwrightInstances.values()', 'PlaywrightRuntime.instance().state.allPlaywrights()'),
    @('PlaywrightManager.STATE.playwrightInstances.clear()', 'PlaywrightRuntime.instance().state.clearPlaywrights()'),
    @('PlaywrightManager.STATE.playwrightInstances.get(', 'PlaywrightRuntime.instance().state.getPlaywright('),
    @('PlaywrightManager.STATE.playwrightInstances.put(', 'PlaywrightRuntime.instance().state.putPlaywright('),
    @('PlaywrightManager.STATE.playwrightInstances.remove(', 'PlaywrightRuntime.instance().state.removePlaywright('),

    @('PlaywrightManager.STATE.browserInstances.entrySet()', 'PlaywrightRuntime.instance().state.browserEntries()'),
    @('PlaywrightManager.STATE.browserInstances.values()', 'PlaywrightRuntime.instance().state.allBrowsers()'),
    @('PlaywrightManager.STATE.browserInstances.clear()', 'PlaywrightRuntime.instance().state.clearBrowsers()'),
    @('PlaywrightManager.STATE.browserInstances.containsValue(', 'PlaywrightRuntime.instance().state.containsBrowserInstance('),
    @('PlaywrightManager.STATE.browserInstances.get(', 'PlaywrightRuntime.instance().state.getBrowser('),
    @('PlaywrightManager.STATE.browserInstances.put(', 'PlaywrightRuntime.instance().state.putBrowser('),
    @('PlaywrightManager.STATE.browserInstances.remove(', 'PlaywrightRuntime.instance().state.removeBrowser('),

    @('PlaywrightManager.STATE.disconnectedBrowsers.add(', 'PlaywrightRuntime.instance().state.markDisconnected('),
    @('PlaywrightManager.STATE.disconnectedBrowsers.remove(', 'PlaywrightRuntime.instance().state.clearDisconnected('),
    @('PlaywrightManager.STATE.disconnectedBrowsers.contains(', 'PlaywrightRuntime.instance().state.isDisconnected('),

    @('PlaywrightManager.STATE.closingBrowsers.add(', 'PlaywrightRuntime.instance().state.markClosing('),
    @('PlaywrightManager.STATE.closingBrowsers.contains(', 'PlaywrightRuntime.instance().state.isClosing('),

    @('PlaywrightManager.STATE.retiredConfigIds.add(', 'PlaywrightRuntime.instance().state.markRetired('),
    @('PlaywrightManager.STATE.retiredConfigIds.remove(', 'PlaywrightRuntime.instance().state.clearRetired('),
    @('PlaywrightManager.STATE.retiredConfigIds.contains(', 'PlaywrightRuntime.instance().state.isRetired('),
    @('PlaywrightManager.STATE.retiredConfigIds.clear()', 'PlaywrightRuntime.instance().state.clearRetiredAll()'),

    @('PlaywrightManager.STATE.fullInit.get()', 'PlaywrightRuntime.instance().state.isFullInit()'),
    @('PlaywrightManager.STATE.fullInit.set(true)', 'PlaywrightRuntime.instance().state.markFullInit()')
)

$dirs = @(
    (Join-Path $root 'web\src\main\java'),
    (Join-Path $root 'web\src\test\java'),
    (Join-Path $root 'test-automation\src\test\java')
)

foreach ($dir in $dirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Recurse -Filter *.java | ForEach-Object {
        $path = $_.FullName
        $text = [System.IO.File]::ReadAllText($path)
        $orig = $text
        foreach ($p in $pairs) {
            $text = $text.Replace($p[0], $p[1])
        }
        if ($text -ne $orig) {
            [System.IO.File]::WriteAllText($path, $text, $enc)
            Write-Output ('MIGRATED: ' + $_.Name)
        }
    }
}

# report leftovers
Write-Output '--- LEFTOVER PlaywrightManager.STATE references ---'
foreach ($dir in $dirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Recurse -Filter *.java | ForEach-Object {
        $n = 0
        Get-Content $_.FullName | ForEach-Object {
            if ($_ -match 'PlaywrightManager\.STATE') {
                $n++
                Write-Output ('  ' + $_.FullName.Substring($root.Length + 1) + ' :: ' + $_.Trim())
            }
        }
    }
}
