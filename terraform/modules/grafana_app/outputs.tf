output "url" {
  value = "https://${azurerm_container_app.grafana.ingress[0].fqdn}"
}

output "app_name" {
  value = azurerm_container_app.grafana.name
}

output "service_principal_object_id" {
  value = azuread_service_principal.grafana.object_id
}

output "app_role_ids" {
  value = { for role, id in random_uuid.role : role => id.result }
}
