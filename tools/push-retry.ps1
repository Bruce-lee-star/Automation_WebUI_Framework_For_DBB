# Auto-retry git push until success (network-recovery friendly).
# Usage: pwsh tools/push-retry.ps1 [-Remote origin] [-Branch DEV-V3] [-MaxAttempts 60] [-BaseWaitSec 15]
param(
    [string]$Remote   = "origin",
    [string]$Branch   = "DEV-V3",
    [int]   $MaxAttempts = 60,
    [int]   $BaseWaitSec = 15
)

$ErrorActionPreference = "Continue"
$buffer = "1048576000"   # 1 GiB post buffer (avoids curl 52 RPC reset on large packs)

for ($i = 1; $i -le $MaxAttempts; $i++) {
    $wait = [math]::Min($BaseWaitSec * [math]::Ceiling($i / 3), 120)
    Write-Host ""
    Write-Host "=== push attempt $i/$MaxAttempts @ $(Get-Date -Format 'HH:mm:ss') ==="
    git -c http.postBuffer=$buffer push $Remote $Branch 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "PUSH SUCCEEDED after $i attempt(s)."
        exit 0
    }
    Write-Host "attempt $i failed (exit $LASTEXITCODE); sleeping $wait s before retry..."
    Start-Sleep -Seconds $wait
}

Write-Host ""
Write-Host "ALL $MaxAttempts ATTEMPTS FAILED. Network may still be unreachable; rerun later."
exit 1
