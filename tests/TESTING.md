# API Testing Guide

## 1) Postman

Import files:

- `tests/postman/Notification-System.postman_collection.json`
- `tests/postman/Notification-System.local.postman_environment.json`

Then run requests in order:

1. Public - Root
2. Public - Health
3. Secured - Create Notification
4. Secured - Duplicate Notification Check
5. Secured - System Stats
6. Secured - Rate Limit Probe (run multiple times quickly)

## 2) PowerShell smoke test

```powershell
Set-Location "c:\Users\YashSiwach\OneDrive - EPAM\Notification system"
./tests/scripts/smoke-test.ps1
```

Optional params:

```powershell
./tests/scripts/smoke-test.ps1 -BaseUrl "http://localhost:8080" -ApiKey "change-me-in-prod" -UserId "user-42"
```

## 3) Bash/curl smoke test

```bash
cd "Notification system"
chmod +x tests/scripts/smoke-test.sh
./tests/scripts/smoke-test.sh
```

## Notes

- API key required for `/api/**` endpoints.
- Reusing the same idempotency key should return `duplicate=true`.
- For new notifications every time, generate a fresh idempotency key.
