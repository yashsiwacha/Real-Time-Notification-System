param(
    [Parameter(Mandatory = $true)] [string]$SubscriptionId,
    [Parameter(Mandatory = $true)] [string]$ResourceGroup,
    [Parameter(Mandatory = $true)] [string]$Location,
    [Parameter(Mandatory = $true)] [string]$AcrName,
    [Parameter(Mandatory = $true)] [string]$ContainerAppEnvName,
    [Parameter(Mandatory = $true)] [string]$ContainerAppName,
    [Parameter(Mandatory = $true)] [string]$PostgresServerName,
    [Parameter(Mandatory = $true)] [string]$PostgresAdminUser,
    [Parameter(Mandatory = $true)] [string]$PostgresAdminPassword,
    [Parameter(Mandatory = $true)] [string]$RedisName,
    [Parameter(Mandatory = $true)] [string]$ApiKey,
    [string]$ImageTag = "v1"
)

$ErrorActionPreference = "Stop"

Write-Host "== Azure Deploy: Real-Time Notification System ==" -ForegroundColor Cyan

$repoRoot = Split-Path -Path $PSScriptRoot -Parent | Split-Path -Parent
Set-Location $repoRoot

az account set --subscription $SubscriptionId

Write-Host "[1/10] Create resource group"
az group create --name $ResourceGroup --location $Location | Out-Null

Write-Host "[2/10] Create Azure Container Registry"
az acr create --resource-group $ResourceGroup --name $AcrName --sku Basic --admin-enabled true | Out-Null

$acrLoginServer = az acr show --name $AcrName --resource-group $ResourceGroup --query loginServer -o tsv

Write-Host "[3/10] Build and push image"
az acr login --name $AcrName | Out-Null
docker build -t "$acrLoginServer/notification-system:$ImageTag" .
docker push "$acrLoginServer/notification-system:$ImageTag"

Write-Host "[4/10] Create PostgreSQL Flexible Server"
az postgres flexible-server create `
  --resource-group $ResourceGroup `
  --name $PostgresServerName `
  --location $Location `
  --admin-user $PostgresAdminUser `
  --admin-password $PostgresAdminPassword `
  --sku-name Standard_B1ms `
  --tier Burstable `
  --version 16 `
  --storage-size 32 `
  --public-access 0.0.0.0 | Out-Null

Write-Host "[5/10] Create notifications database"
az postgres flexible-server db create --resource-group $ResourceGroup --server-name $PostgresServerName --database-name notifications | Out-Null

Write-Host "[6/10] Create Azure Cache for Redis"
az redis create --resource-group $ResourceGroup --name $RedisName --location $Location --sku Basic --vm-size c0 | Out-Null

$redisHost = az redis show --resource-group $ResourceGroup --name $RedisName --query hostName -o tsv
$redisKey = az redis list-keys --resource-group $ResourceGroup --name $RedisName --query primaryKey -o tsv

Write-Host "[7/10] Create Container Apps environment"
az containerapp env create --name $ContainerAppEnvName --resource-group $ResourceGroup --location $Location | Out-Null

Write-Host "[8/10] Create Container App"
az containerapp create `
  --name $ContainerAppName `
  --resource-group $ResourceGroup `
  --environment $ContainerAppEnvName `
  --image "$acrLoginServer/notification-system:$ImageTag" `
  --target-port 8080 `
  --ingress external `
  --registry-server $acrLoginServer `
  --query properties.configuration.ingress.fqdn -o tsv | Out-Null

$postgresHost = "$PostgresServerName.postgres.database.azure.com"
$dbUrl = "jdbc:postgresql://$postgresHost:5432/notifications?sslmode=require"

Write-Host "[9/10] Configure app secrets and environment"
az containerapp secret set --name $ContainerAppName --resource-group $ResourceGroup --secrets `
  api-key-secret=$ApiKey `
  db-password-secret=$PostgresAdminPassword `
  redis-password-secret=$redisKey | Out-Null

az containerapp update `
  --name $ContainerAppName `
  --resource-group $ResourceGroup `
  --set-env-vars `
  SPRING_PROFILES_ACTIVE=prod `
  DB_URL="$dbUrl" `
  DB_USERNAME=$PostgresAdminUser `
  DB_PASSWORD=secretref:db-password-secret `
  REDIS_HOST=$redisHost `
  REDIS_PORT=6380 `
  REDIS_PASSWORD=secretref:redis-password-secret `
  REDIS_SSL_ENABLED=true `
  API_KEY=secretref:api-key-secret | Out-Null

$fqdn = az containerapp show --name $ContainerAppName --resource-group $ResourceGroup --query properties.configuration.ingress.fqdn -o tsv

Write-Host "[10/10] Lock CORS/WebSocket origins to deployed URL"
az containerapp update `
  --name $ContainerAppName `
  --resource-group $ResourceGroup `
  --set-env-vars ALLOWED_ORIGINS="https://$fqdn" | Out-Null

Write-Host "Deployment completed" -ForegroundColor Green
Write-Host "App URL: https://$fqdn"
Write-Host "Health: https://$fqdn/actuator/health"
