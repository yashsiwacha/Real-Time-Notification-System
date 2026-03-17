param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = "change-me-in-prod",
    [string]$UserId = "user-42"
)

$ErrorActionPreference = "Stop"

Write-Host "== Notification System Smoke Test ==" -ForegroundColor Cyan
Write-Host "BaseUrl: $BaseUrl"

$root = Invoke-RestMethod -Uri "$BaseUrl/" -Method Get -TimeoutSec 20
Write-Host "[OK] Root endpoint reachable: $($root.status)"

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get -TimeoutSec 20
Write-Host "[OK] Health status: $($health.status)"

$idempotencyKey = "smoke-$UserId-$(Get-Date -Format yyyyMMddHHmmssfff)"
$headers = @{
    "X-API-KEY" = $ApiKey
    "Content-Type" = "application/json"
}
$payload = @{
    userId = $UserId
    type = "ORDER_STATUS"
    message = "Smoke test notification"
    idempotencyKey = $idempotencyKey
    metadata = @{
        orderId = "smoke-001"
        etaMinutes = "10"
    }
} | ConvertTo-Json -Depth 4

$create = Invoke-RestMethod -Uri "$BaseUrl/api/notifications" -Method Post -Headers $headers -Body $payload -TimeoutSec 20
Write-Host "[OK] Create notification: status=$($create.status), duplicate=$($create.duplicate), id=$($create.notificationId)"

$duplicate = Invoke-RestMethod -Uri "$BaseUrl/api/notifications" -Method Post -Headers $headers -Body $payload -TimeoutSec 20
Write-Host "[OK] Duplicate check: status=$($duplicate.status), duplicate=$($duplicate.duplicate), id=$($duplicate.notificationId)"

$stats = Invoke-RestMethod -Uri "$BaseUrl/api/notifications/system-stats" -Method Get -Headers @{"X-API-KEY" = $ApiKey} -TimeoutSec 20
Write-Host "[OK] Stats: $(($stats | ConvertTo-Json -Compress))"

Write-Host "Smoke test completed." -ForegroundColor Green
