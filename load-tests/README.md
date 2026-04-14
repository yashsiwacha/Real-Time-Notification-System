# Load Testing (k6)

This project includes a k6 script to validate throughput and latency with high concurrency.

## What it validates

- Sustained throughput near 500 events/sec
- p95 API latency for publish endpoint
- Failure rate and overload indicators (429 and 5xx)

## Scenario profile

The script uses a constant-arrival-rate scenario.

Default profile:

- Target: `500` events/sec
- Duration: `3m`
- Thresholds: `p95 < 800ms`, failure rate `< 2%`, accepted rate `> 98%`

## Run locally

1. Start infrastructure and app:

```powershell
docker compose up --build
```

2. Run k6 load test:

```powershell
k6 run ./load-tests/k6-notifications.js --env BASE_URL=http://localhost:8080 --env ADMIN_USERNAME=admin --env ADMIN_PASSWORD=admin123 --env TARGET_EPS=500 --env TEST_DURATION=3m
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

- `http_req_duration` (`p(95)` for latency)
- `http_req_failed` (failure percent)
- `accepted_rate` (successful enqueue ratio)
- `rate_limited_total` and `server_error_total` (what broke during overload)
