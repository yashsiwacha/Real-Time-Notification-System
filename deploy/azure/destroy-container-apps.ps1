param(
    [Parameter(Mandatory = $true)] [string]$SubscriptionId,
    [Parameter(Mandatory = $true)] [string]$ResourceGroup
)

$ErrorActionPreference = "Stop"

az account set --subscription $SubscriptionId
az group delete --name $ResourceGroup --yes --no-wait

Write-Host "Deletion initiated for resource group: $ResourceGroup" -ForegroundColor Yellow
