output "backend_subnet_id" {
  value = azurerm_subnet.backend.id
}

output "private_endpoint_subnet_id" {
  description = "Subnet dedicated to private endpoint NICs."
  value       = azurerm_subnet.private_endpoints.id
}

output "db_subnet_id" {
  value = azurerm_subnet.db.id
}

output "container_app_env_id" {
  value = azurerm_container_app_environment.main.id
}

output "id" {
  description = "The ID of the virtual network."
  value       = azurerm_virtual_network.main.id
}

output "storage_subnet_id" {
  description = "Subnet holding the blob private endpoint NIC."
  value       = azurerm_subnet.storage.id
}
