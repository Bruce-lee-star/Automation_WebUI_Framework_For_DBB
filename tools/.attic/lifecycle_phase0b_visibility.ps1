# Phase 0b: remaining 3 cross-subpackage fixes (types + visibility). UTF8 no-BOM.
$enc = New-Object System.Text.UTF8Encoding($false)
function Edit-File {
    param([string]$Path,[string]$Old,[string]$New)
    if (-not (Test-Path $Path)) { Write-Host "MISSING: $Path"; return }
    $content = $enc.GetString([System.IO.File]::ReadAllBytes($Path))
    if ($content.Length -gt 0 -and $content[0] -eq [char]0xFEFF) { $content = $content.Substring(1) }
    if (-not $content.Contains($Old)) { Write-Host "WARN NOT FOUND in ${Path}: >>>${Old}<<<"; return }
    [System.IO.File]::WriteAllBytes($Path, $enc.GetBytes($content.Replace($Old, $New)))
    Write-Host "OK edited: $Path"
}

$base = 'd:\IdeaProject\Automation_WebUI_Framework_For_DBB\web\src\main\java\com\hsbc\cmb\hk\dbb\automation\framework\web\lifecycle'
$PCM = Join-Path $base 'bootstrap\PlaywrightContextManager.java'
$PIN = Join-Path $base 'bootstrap\PlaywrightInitializer.java'
$PM  = Join-Path $base 'PlaywrightManager.java'

# 1) type mismatch: customOptions() returns interface CustomOptions, not impl CustomOptionsManager
Edit-File $PCM '        CustomOptionsManager cm = PlaywrightManager.customOptions();' '        CustomOptions cm = PlaywrightManager.customOptions();'

# 2) ensureBrowsersInstalled() package-private -> public (called by BrowserStartupImpl)
Edit-File $PIN '    static boolean ensureBrowsersInstalled() {' '    public static boolean ensureBrowsersInstalled() {'

# 3) getPageThreadLocal() package-private -> public (called by PlaywrightScreenshotManager)
Edit-File $PM '    static Page getPageThreadLocal() {' '    public static Page getPageThreadLocal() {'

Write-Host "=== Phase 0b complete ==="
