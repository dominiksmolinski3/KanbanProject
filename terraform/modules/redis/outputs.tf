output "hostname" {
  value = azurerm_managed_redis.main.hostname
}

output "port" {
  value = azurerm_managed_redis.main.default_database[0].port
}
