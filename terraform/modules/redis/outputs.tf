output "hostname" {
  value = azurerm_managed_redis.main.hostname
}

# Not 6380 - that was the classic Cache for Redis TLS port. Managed Redis answers on 10000, and the
# encryption comes from default_database.client_protocol rather than from the port chosen.
output "port" {
  value = azurerm_managed_redis.main.default_database[0].port
}
