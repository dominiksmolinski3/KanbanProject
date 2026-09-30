output "hostname" {
  value = azurerm_managed_redis.main.hostname
}

output "id" {
  value = azurerm_managed_redis.main.id
}

output "port" {
  value = azurerm_managed_redis.main.default_database[0].port
}
