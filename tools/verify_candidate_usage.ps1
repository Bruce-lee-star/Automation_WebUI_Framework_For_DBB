# Verifies a candidate list of type names against ALL file types (java, xml, properties,
# conf, feature, json, yml) so reflection/SPI/config-driven usages are not missed.
# Pure ASCII.

$root = 'd:\IdeaProject\Automation_WebUI_Framework_For_DBB'
$candidates = @(
    'FileUtils',
    'FileReader',
    'HttpStatus',
    'RandomStringUtil',
    'RouteContextHandle',
    'RouteLifecycleAdapter',
    'RouteRegistrar',
    'ConditionalWhen',
    'MonitorFailureReportWriterSink',
    'SerenityConfigResolver',
    'SharedBrowserContextIsolationE2E',
    'ConcurrentExecutionGlue',
    'RecoveryAction'
)

$ext = @('*.java', '*.xml', '*.properties', '*.conf', '*.feature', '*.json', '*.yml', '*.yaml', '*.txt', '*.md')
$files = Get-ChildItem -Path $root -Recurse -Include $ext -ErrorAction SilentlyContinue |
         Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -notmatch '\\.git\\' -and $_.FullName -notmatch '\\tools\\' }

foreach ($name in $candidates) {
    $hits = @()
    foreach ($f in $files) {
        $txt = [System.IO.File]::ReadAllText($f.FullName)
        if ($txt -cmatch ('\b' + [regex]::Escape($name) + '\b')) {
            $hits += ($f.FullName.Substring($root.Length + 1))
        }
    }
    Write-Output ('=== ' + $name + ' : ' + $hits.Count + ' file(s) ===')
    foreach ($h in ($hits | Select-Object -First 8)) { Write-Output ('    ' + $h) }
}
