output "container_app_url" {
  description = "Stable public URL of the Azure Container App ingress. Uses the app-scoped FQDN, which does not change between revisions."
  value       = "https://${azurerm_container_app.main.ingress[0].fqdn}"
}

output "container_app_id" {
  description = "Resource ID of the Azure Container App."
  value       = azurerm_container_app.main.id
}
