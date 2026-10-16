. (Join-Path $PSScriptRoot 'Common.ps1')
$taskHome = Get-AppHome
$taskInstance = Get-LiveInstance $taskHome
if (-not $taskInstance) { Write-Host 'No verified doc2TeX instance is running for this data folder.'; return }
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($taskInstance.port)/api/system/stop" -Headers @{ 'X-Doc2tex-Control' = $taskInstance.token } -TimeoutSec 8 | Out-Null
for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
    if (-not (Get-Process -Id $taskInstance.pid -ErrorAction SilentlyContinue)) { Write-Host 'doc2TeX stopped. Projects, versions, settings and browser drafts are retained.'; return }
    Start-Sleep -Milliseconds 500
}
throw 'Shutdown is still in progress. Wait and inspect the startup log before upgrading; do not replace an app while it is running.'
