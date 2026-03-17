#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-change-me-in-prod}"
USER_ID="${USER_ID:-user-42}"

echo "== Notification System Smoke Test =="
echo "BaseUrl: ${BASE_URL}"

echo "[1] Root"
curl -sS "${BASE_URL}/" | jq .

echo "[2] Health"
curl -sS "${BASE_URL}/actuator/health" | jq .

IDEMPOTENCY_KEY="smoke-${USER_ID}-$(date +%s%3N)"
PAYLOAD=$(cat <<JSON
{
  "userId": "${USER_ID}",
  "type": "ORDER_STATUS",
  "message": "Smoke test notification",
  "idempotencyKey": "${IDEMPOTENCY_KEY}",
  "metadata": {
    "orderId": "smoke-001",
    "etaMinutes": "10"
  }
}
JSON
)

echo "[3] Create"
curl -sS -X POST "${BASE_URL}/api/notifications" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: ${API_KEY}" \
  -d "${PAYLOAD}" | jq .

echo "[4] Duplicate check"
curl -sS -X POST "${BASE_URL}/api/notifications" \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: ${API_KEY}" \
  -d "${PAYLOAD}" | jq .

echo "[5] Stats"
curl -sS "${BASE_URL}/api/notifications/system-stats" \
  -H "X-API-KEY: ${API_KEY}" | jq .

echo "Smoke test completed."
