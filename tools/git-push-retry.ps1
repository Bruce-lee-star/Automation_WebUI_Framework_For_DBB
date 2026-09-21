#!/usr/bin/env pwsh
# git-push-retry.ps1
# git push with exponential-backoff retry (handles transient network / remote failures).
# Usage: powershell -ExecutionPolicy Bypass -File tools/git-push-retry.ps1 [-Remote origin] [-MaxAttempts 5]
param(
    [string]$Remote = "origin",
    [int]$MaxAttempts = 5
)

$branch = git rev-parse --abbrev-ref HEAD
if (-not $branch -or $branch -eq "HEAD") {
    Write-Error "Cannot determine current branch, abort."
    exit 1
}

Write-Host ("[git-push-retry] target: " + ${Remote} + "/" + ${branch} + ", max attempts: " + ${MaxAttempts})

for ($i = 1; $i -le ${MaxAttempts}; $i++) {
    Write-Host ("[git-push-retry] attempt " + ${i} + "/" + ${MaxAttempts} + ": git push " + ${Remote} + " " + ${branch})
    git push ${Remote} ${branch}
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[git-push-retry] push succeeded."
        exit 0
    }
    if ($i -lt ${MaxAttempts}) {
        $delay = [math]::Pow(2, ${i})
        Write-Warning ("[git-push-retry] push failed (exit=" + ${LASTEXITCODE} + "), retry in " + ${delay} + "s...")
        Start-Sleep -Seconds ${delay}
    }
}

Write-Error ("[git-push-retry] failed after " + ${MaxAttempts} + " attempts.")
exit 1
