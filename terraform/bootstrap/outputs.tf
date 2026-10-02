output "drift_client_id" {
  description = "AZURE_CLIENT_ID in the terraform-drift GitHub environment."
  value       = azurerm_user_assigned_identity.drift.client_id
}

output "tenant_id" {
  description = "AZURE_TENANT_ID in the terraform-drift GitHub environment."
  value       = azurerm_user_assigned_identity.drift.tenant_id
}

output "subscription_id" {
  description = "AZURE_SUBSCRIPTION_ID in the terraform-drift GitHub environment."
  value       = data.azurerm_subscription.current.subscription_id
}

output "operator_object_id" {
  description = "TERRAFORM_OPERATOR_OBJECT_ID in the terraform-drift GitHub environment: whoever applies, which is who the vault's Secrets Officer assignment names."
  value       = data.azurerm_client_config.current.object_id
}
