# Render Deployment Guide (Neon + Render)

This project is ready for Render deployment with Neon as the external PostgreSQL database.

## 1) Push latest code

Your repository already contains:

- `render.yaml`
- `Dockerfile`
- production Spring profile config

## 2) Create Neon database

1. Login to Neon dashboard
2. Create a new project and database (for example: `notifications`)
3. Open Connection Details and copy the JDBC connection string
4. Make sure SSL mode is required in the URL, for example:

```text
jdbc:postgresql://ep-xxxxxxx-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require
```

## 3) Create Blueprint on Render

1. Login to Render dashboard
2. Click `New +` -> `Blueprint`
3. Connect your GitHub repository:
   - `yashsiwacha/Real-Time-Notification-System`
4. Select branch: `main`
5. Render detects `render.yaml` and plans 2 resources:
   - Web service `notification-system`
   - Redis `notification-redis`
6. Click `Apply`
7. Open the `notification-system` service -> `Environment`
8. Set `DB_URL` to your Neon JDBC URL
9. Save changes and redeploy

## 4) Wait for initial build

Render will:

1. Build Docker image
2. Provision Redis
3. Use your configured `DB_URL` for Neon Postgres
4. Start service and run health checks at `/actuator/health`

## 5) Verify deployment

After deploy, open:

1. `https://<your-render-url>/actuator/health`
2. `https://<your-render-url>/demo`

Expected health response:

```json
{"status":"UP"}
```

## 6) API testing on Render

Use API key from Render dashboard:

1. Open service `notification-system`
2. Go to `Environment`
3. Copy `API_KEY`

Then call:

```powershell
$baseUrl = "https://<your-render-url>"
$headers = @{"X-API-KEY"="<api-key>";"Content-Type"="application/json"}
$body = @{
  userId = "user-42"
  type = "ORDER_STATUS"
  message = "Your order is out for delivery"
  idempotencyKey = "render-demo-$(Get-Date -Format yyyyMMddHHmmss)"
  metadata = @{ orderId = "8843"; etaMinutes = "18" }
} | ConvertTo-Json -Depth 4

Invoke-RestMethod -Uri "$baseUrl/api/notifications" -Method Post -Headers $headers -Body $body
```

## 7) Important notes

1. Update `ALLOWED_ORIGINS` after first deploy if your Render URL differs from `https://notification-system.onrender.com`.
2. Free plans may spin down when idle.
3. For production traffic, use paid plans and managed secret rotation.
4. For Neon, keep `sslmode=require` in `DB_URL`.
