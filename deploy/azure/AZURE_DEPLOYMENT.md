# Azure Deployment Playbook (Container Apps)

This playbook deploys the notification system to Azure with managed services:

- Azure Container Apps (application)
- Azure Database for PostgreSQL Flexible Server
- Azure Cache for Redis
- Azure Container Registry

## Prerequisites

1. Azure subscription with contributor access
2. Azure CLI installed and logged in
3. Docker Desktop running
4. Resource names must be globally unique where required (`AcrName`, `PostgresServerName`, `RedisName`)

## One-command deployment (PowerShell)

Run from project root:

```powershell
./deploy/azure/deploy-container-apps.ps1 `
  -SubscriptionId "<subscription-id>" `
  -ResourceGroup "rg-notification-prod" `
  -Location "eastus" `
  -AcrName "acrnotifprod001" `
  -ContainerAppEnvName "cae-notification-prod" `
  -ContainerAppName "ca-notification-api" `
  -PostgresServerName "psqlnotifprod001" `
  -PostgresAdminUser "pgadminuser" `
  -PostgresAdminPassword "<strong-password>" `
  -RedisName "redisnotifprod001" `
  -ApiKey "<strong-api-key>" `
  -ImageTag "v1"
```

The script prints:

- app URL
- health URL

## Manual command sequence

```powershell
az group create --name rg-notification-prod --location eastus
az acr create --resource-group rg-notification-prod --name acrnotifprod001 --sku Basic --admin-enabled true
az acr login --name acrnotifprod001
$acr = az acr show --name acrnotifprod001 --resource-group rg-notification-prod --query loginServer -o tsv

docker build -t "$acr/notification-system:v1" .
docker push "$acr/notification-system:v1"

az postgres flexible-server create --resource-group rg-notification-prod --name psqlnotifprod001 --location eastus --admin-user pgadminuser --admin-password "<strong-password>" --sku-name Standard_B1ms --tier Burstable --version 16 --storage-size 32 --public-access 0.0.0.0
az postgres flexible-server db create --resource-group rg-notification-prod --server-name psqlnotifprod001 --database-name notifications

az redis create --resource-group rg-notification-prod --name redisnotifprod001 --location eastus --sku Basic --vm-size c0
$redisHost = az redis show --resource-group rg-notification-prod --name redisnotifprod001 --query hostName -o tsv
$redisKey = az redis list-keys --resource-group rg-notification-prod --name redisnotifprod001 --query primaryKey -o tsv

az containerapp env create --name cae-notification-prod --resource-group rg-notification-prod --location eastus
az containerapp create --name ca-notification-api --resource-group rg-notification-prod --environment cae-notification-prod --image "$acr/notification-system:v1" --target-port 8080 --ingress external --registry-server $acr

$fqdn = az containerapp show --name ca-notification-api --resource-group rg-notification-prod --query properties.configuration.ingress.fqdn -o tsv
$dbUrl = "jdbc:postgresql://psqlnotifprod001.postgres.database.azure.com:5432/notifications?sslmode=require"

az containerapp secret set --name ca-notification-api --resource-group rg-notification-prod --secrets api-key-secret="<strong-api-key>" db-password-secret="<strong-password>" redis-password-secret="$redisKey"
az containerapp update --name ca-notification-api --resource-group rg-notification-prod --set-env-vars SPRING_PROFILES_ACTIVE=prod DB_URL="$dbUrl" DB_USERNAME=pgadminuser DB_PASSWORD=secretref:db-password-secret REDIS_HOST=$redisHost REDIS_PORT=6380 REDIS_PASSWORD=secretref:redis-password-secret REDIS_SSL_ENABLED=true API_KEY=secretref:api-key-secret ALLOWED_ORIGINS="https://$fqdn"
```

## Validate deployment

```powershell
Invoke-RestMethod -Uri "https://<fqdn>/actuator/health" -Method Get
```

Expected:

```json
{"status":"UP"}
```

## Teardown

```powershell
./deploy/azure/destroy-container-apps.ps1 -SubscriptionId "<subscription-id>" -ResourceGroup "rg-notification-prod"
```
