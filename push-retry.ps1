# Git Push 自动重试脚本
# 用法: .\push-retry.ps1 [-MaxRetries 10] [-DelaySeconds 30]

param(
    [int]$MaxRetries = 10,
    [int]$DelaySeconds = 30
)

$retryCount = 0
$success = $false

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Git Push Auto-Retry Script" -ForegroundColor Cyan
Write-Host "  Max Retries: $MaxRetries" -ForegroundColor Cyan
Write-Host "  Delay Between Retries: ${DelaySeconds}s" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

while ($retryCount -lt $MaxRetries) {
    $retryCount++
    Write-Host "[Attempt $retryCount/$MaxRetries] Pushing to origin/master..." -ForegroundColor Yellow

    $output = git push origin master 2>&1
    $exitCode = $LASTEXITCODE

    if ($exitCode -eq 0) {
        Write-Host "[SUCCESS] Push completed on attempt $retryCount!" -ForegroundColor Green
        $success = $true
        break
    }
    else {
        Write-Host "[FAILED] Exit code: $exitCode" -ForegroundColor Red
        Write-Host "Error: $output" -ForegroundColor Gray

        if ($retryCount -lt $MaxRetries) {
            Write-Host "Retrying in ${DelaySeconds} seconds..." -ForegroundColor Yellow
            Start-Sleep -Seconds $DelaySeconds
        }
    }
}

Write-Host ""
if ($success) {
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  Push Successful!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
}
else {
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "  Push Failed After $MaxRetries Attempts" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
}

exit $(if ($success) { 0 } else { 1 })
