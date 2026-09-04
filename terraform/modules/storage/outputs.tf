output "id" {
  description = "Resource id of the attachment storage account, for the role assignment on it."
  value       = azurerm_storage_account.attachments.id
}

output "blob_endpoint" {
  description = "The blob service endpoint the application is pointed at. Not a secret - it is reached with a token."
  value       = azurerm_storage_account.attachments.primary_blob_endpoint
}

output "name" {
  description = "The generated account name, which carries a random suffix because the namespace is global."
  value       = azurerm_storage_account.attachments.name
}
