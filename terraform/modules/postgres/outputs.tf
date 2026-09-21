output "postgres_server_name" {
  value = azurerm_postgresql_flexible_server.main.name
}

output "postgres_db_name" {
  value = azurerm_postgresql_flexible_server_database.main.name
}

output "postgres_server_id" {
  value = azurerm_postgresql_flexible_server.main.id
}

output "usable_connections" {
  description = "Connections available to ordinary logins: the SKU's max_connections less the superuser reserve. The API's pool budget must fit inside this."
  value       = local.max_connections_by_sku[var.sku_name] - local.superuser_reserved_connections

  precondition {
    condition     = contains(keys(local.max_connections_by_sku), var.sku_name)
    error_message = "No max_connections recorded for sku_name ${var.sku_name}. Add it to local.max_connections_by_sku in modules/postgres/main.tf - Azure sizes the limit from the SKU and does not publish it as an attribute, so there is nothing to read it from."
  }
}
