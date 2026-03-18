# Load Testing (k6)

This project includes a k6 script to validate throughput and latency with high concurrency.

## What it validates

- Requests per second under load
- Average and p95 latency
- Failure rate under high VU count

## Scenario profile

The script ramps up to 10k virtual users in stages.

## Run locally

1. Start infrastructure and app:

```powershell
docker compose up --build
```

2. Run k6 load test:

```powershell
k6 run ./load-tests/k6-notifications.js --env BASE_URL=http://localhost:8080 --env ADMIN_USERNAME=admin --env ADMIN_PASSWORD=admin123
```

This default run uses one setup-stage JWT and reuses it across VUs, which is the recommended steady-state workload for notification throughput testing.

To simulate auth-heavy behavior (login on every iteration), run:

```powershell
k6 run ./load-tests/k6-notifications.js --env BASE_URL=http://localhost:8080 --env ADMIN_USERNAME=admin --env ADMIN_PASSWORD=admin123 --env LOGIN_EACH_ITERATION=true
```

Optional token refresh control in steady-state mode:

```powershell
--env TOKEN_REFRESH_ITERATIONS=500
```

Disable setup-token mode if you want per-VU token acquisition:

```powershell
--env USE_SETUP_TOKEN=false
```

3. Review output for:

- `http_req_duration` (latency)
- `http_req_failed` (failure percent)
- request rate (TPS/RPS)
