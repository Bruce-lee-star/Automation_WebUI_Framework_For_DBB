# Phase 0: atomic visibility promotion for lifecycle cross-subpackage compile fix.
# UTF8 no-BOM read/write; each edit is a literal string Replace; warns if pattern missing.
$ErrorActionPreference = 'Continue'
$enc = New-Object System.Text.UTF8Encoding($false)

function Edit-File {
    param(
        [string]$Path,
        [string]$Old,
        [string]$New
    )
    if (-not (Test-Path $Path)) { Write-Host "MISSING: $Path"; return }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $content = $enc.GetString($bytes)
    if ($content.Length -gt 0 -and $content[0] -eq [char]0xFEFF) { $content = $content.Substring(1) }
    if (-not $content.Contains($Old)) {
        Write-Host "WARN NOT FOUND in ${Path}: >>>${Old}<<<"
        return
    }
    $content = $content.Replace($Old, $New)
    [System.IO.File]::WriteAllBytes($Path, $enc.GetBytes($content))
    Write-Host "OK edited: $Path"
}

$base = 'd:\IdeaProject\Automation_WebUI_Framework_For_DBB\web\src\main\java\com\hsbc\cmb\hk\dbb\automation\framework\web\lifecycle'
$PM  = Join-Path $base 'PlaywrightManager.java'
$PRS = Join-Path $base 'PlaywrightRuntimeState.java'
$COM = Join-Path $base 'context\CustomOptionsManager.java'
$TCB = Join-Path $base 'serenity\TestContextBridge.java'
$SBB = Join-Path $base 'serenity\SerenityBusBridge.java'
$PIN = Join-Path $base 'bootstrap\PlaywrightInitializer.java'
$PCM = Join-Path $base 'bootstrap\PlaywrightContextManager.java'
$PSB = Join-Path $base 'serenity\PlaywrightSerenityBridge.java'
$CRI = Join-Path $base 'context\ContextRegistryImpl.java'
$PRI = Join-Path $base 'page\PageRegistryImpl.java'

# ---- PlaywrightManager: promote package-private members referenced by subpackages ----
Edit-File $PM '    static final String SHARED_KEY_PREFIX = "shared:";' '    public static final String SHARED_KEY_PREFIX = "shared:";'
Edit-File $PM '    static final ContextKey<BrowserContext> CONTEXT_KEY = ContextKey.of("playwrightManager.context", BrowserContext.class);' '    public static final ContextKey<BrowserContext> CONTEXT_KEY = ContextKey.of("playwrightManager.context", BrowserContext.class);'
Edit-File $PM '    static final ContextKey<Page> PAGE_KEY = ContextKey.of("playwrightManager.page", Page.class);' '    public static final ContextKey<Page> PAGE_KEY = ContextKey.of("playwrightManager.page", Page.class);'
Edit-File $PM '    static final ContextKey<String> CURRENT_CONFIG_ID_KEY = ContextKey.of("playwrightManager.currentConfigId", String.class);' '    public static final ContextKey<String> CURRENT_CONFIG_ID_KEY = ContextKey.of("playwrightManager.currentConfigId", String.class);'
Edit-File $PM '    static final boolean SHARED_BROWSER_MODE = PlaywrightRuntime.instance().browserRegistry.resolveSharedBrowserMode();' '    public static final boolean SHARED_BROWSER_MODE = PlaywrightRuntime.instance().browserRegistry.resolveSharedBrowserMode();'
Edit-File $PM '    static void parkMillis(long millis) {' '    public static void parkMillis(long millis) {'
Edit-File $PM '    static FrameworkState getFrameworkState() {' '    public static FrameworkState getFrameworkState() {'

# ---- PlaywrightRuntimeState: promote the 6 state fields (accessed via PlaywrightManager.STATE.x) ----
Edit-File $PRS '    final ConcurrentMap<String, Playwright> playwrightInstances = new ConcurrentHashMap<>();' '    public final ConcurrentMap<String, Playwright> playwrightInstances = new ConcurrentHashMap<>();'
Edit-File $PRS '    final ConcurrentMap<String, Browser> browserInstances = new ConcurrentHashMap<>();' '    public final ConcurrentMap<String, Browser> browserInstances = new ConcurrentHashMap<>();'
Edit-File $PRS '    final Set<Browser> disconnectedBrowsers = ConcurrentHashMap.newKeySet();' '    public final Set<Browser> disconnectedBrowsers = ConcurrentHashMap.newKeySet();'
Edit-File $PRS '    final Set<Browser> closingBrowsers =' '    public final Set<Browser> closingBrowsers ='
Edit-File $PRS '    final Set<String> retiredConfigIds = ConcurrentHashMap.newKeySet();' '    public final Set<String> retiredConfigIds = ConcurrentHashMap.newKeySet();'
Edit-File $PRS '    final AtomicBoolean fullInit = new AtomicBoolean(false);' '    public final AtomicBoolean fullInit = new AtomicBoolean(false);'

# ---- CustomOptionsManager: promote internal keys + removeAllThreadLocals; fix split-orphan call ----
Edit-File $COM '    static final ContextKey<Path> CUSTOM_STORAGE_STATE_PATH_KEY = ContextKey.of("customOptionsManager.customStorageStatePath", Path.class);' '    public static final ContextKey<Path> CUSTOM_STORAGE_STATE_PATH_KEY = ContextKey.of("customOptionsManager.customStorageStatePath", Path.class);'
Edit-File $COM '    static final ContextKey<String> CUSTOM_STORAGE_STATE_KEY = ContextKey.of("customOptionsManager.customStorageState", String.class);' '    public static final ContextKey<String> CUSTOM_STORAGE_STATE_KEY = ContextKey.of("customOptionsManager.customStorageState", String.class);'
Edit-File $COM '    static final ContextKey<Integer> CUSTOM_VIEWPORT_WIDTH_KEY = ContextKey.of("customOptionsManager.customViewportWidth", Integer.class);' '    public static final ContextKey<Integer> CUSTOM_VIEWPORT_WIDTH_KEY = ContextKey.of("customOptionsManager.customViewportWidth", Integer.class);'
Edit-File $COM '    static final ContextKey<Integer> CUSTOM_VIEWPORT_HEIGHT_KEY = ContextKey.of("customOptionsManager.customViewportHeight", Integer.class);' '    public static final ContextKey<Integer> CUSTOM_VIEWPORT_HEIGHT_KEY = ContextKey.of("customOptionsManager.customViewportHeight", Integer.class);'
Edit-File $COM '    static void removeAllThreadLocals() {' '    public static void removeAllThreadLocals() {'
Edit-File $COM '        PlaywrightManager.scheduleContextRebuild();' '        PlaywrightRuntime.instance().contextRegistry.scheduleContextRebuild();'

# ---- TestContextBridge: drainPageErrors (called by ConcurrentContextExecutor, parent) ----
Edit-File $TCB '    static List<String> drainPageErrors() {' '    public static List<String> drainPageErrors() {'

# ---- SerenityBusBridge: replayFailures (called by ConcurrentContextExecutor, parent) ----
Edit-File $SBB '    static void replayFailures(List<? extends ContextTaskResult<?>> results) {' '    public static void replayFailures(List<? extends ContextTaskResult<?>> results) {'

# ---- PlaywrightInitializer: initializePlaywrightPaths (called by PlaywrightManager static block, parent) ----
Edit-File $PIN '    static void initializePlaywrightPaths() {' '    public static void initializePlaywrightPaths() {'

# ---- PlaywrightContextManager: createContext / createPage / closeContext / closePage (called cross-subpackage) ----
Edit-File $PCM '    static BrowserContext createContext() {' '    public static BrowserContext createContext() {'
Edit-File $PCM '    static Page createPage(BrowserContext context) {' '    public static Page createPage(BrowserContext context) {'
Edit-File $PCM '    static void closeContext(BrowserContext context) {' '    public static void closeContext(BrowserContext context) {'
Edit-File $PCM '    static void closePage(Page page) {' '    public static void closePage(Page page) {'

# ---- PlaywrightSerenityBridge: lifecycle methods called cross-subpackage ----
Edit-File $PSB '    static void cleanupThreadLocals(boolean clearContextAndPage) {' '    public static void cleanupThreadLocals(boolean clearContextAndPage) {'
Edit-File $PSB '    static void createNewContextAndPage() {' '    public static void createNewContextAndPage() {'
Edit-File $PSB '    static void cleanupForScenario() {' '    public static void cleanupForScenario() {'
Edit-File $PSB '    static void cleanupForFeature() {' '    public static void cleanupForFeature() {'

# ---- frameworkState direct-field access -> getFrameworkState() accessor (principle B) ----
Edit-File $CRI '        if (!PlaywrightManager.frameworkState.isInitialized()) {' '        if (!PlaywrightManager.getFrameworkState().isInitialized()) {'
Edit-File $PRI '        if (!PlaywrightManager.frameworkState.isInitialized()) {' '        if (!PlaywrightManager.getFrameworkState().isInitialized()) {'

Write-Host "=== Phase 0 visibility edits complete ==="
