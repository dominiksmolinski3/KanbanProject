
output "backend_subnet_id" {
  value      = azurerm_subnet.backend.id
  depends_on = [azurerm_subnet_network_security_group_association.backend]
}

output "private_endpoint_subnet_id" {
  description = "Subnet dedicated to private endpoint NICs."
  value       = azurerm_subnet.private_endpoints.id
  depends_on  = [azurerm_subnet_network_security_group_association.private_endpoints]
}

output "db_subnet_id" {
  value      = azurerm_subnet.db.id
  depends_on = [azurerm_subnet_network_security_group_association.db]
}

output "container_app_env_id" {
  value = azurerm_container_app_environment.main.id
}

output "container_app_env_default_domain" {
  description = "Domain suffix Azure assigns the Container Apps environment, e.g. \"whimsical-abc123.polandcentral.azurecontainerapps.io\". Combined with an app's own name, this is knowable before the app is created - which is what lets the app be told its own origin without a circular dependency on its own ingress fqdn."
  value       = azurerm_container_app_environment.main.default_domain
}

output "id" {
  description = "The ID of the virtual network."
  value       = azurerm_virtual_network.main.id
}

output "storage_subnet_id" {
  description = "Subnet holding the blob private endpoint NIC."
  value       = azurerm_subnet.storage.id
  depends_on  = [azurerm_subnet_network_security_group_association.storage]
}
