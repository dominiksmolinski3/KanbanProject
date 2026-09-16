resource "azurerm_redis_cache" "main" {
  tags                = var.tags
  name                = "redis-kanban-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name

  capacity = var.capacity
  family   = var.family
  sku_name = var.sku_name

  non_ssl_port_enabled = false
  minimum_tls_version  = "1.2"

  # Closed to the internet, the same posture as the blob storage account and Postgres: the app
  # reaches it over the private endpoint below and is the only thing that ever does.
  public_network_access_enabled = false

  redis_configuration {}
}

resource "azurerm_private_dns_zone" "redis" {
  tags                = var.tags
  name                = "privatelink.redis.cache.windows.net"
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "redis" {
  tags                = var.tags
  name                = "${var.env}-redis-vnet-link"
  private_dns_zone_id = azurerm_private_dns_zone.redis.id
  virtual_network_id  = var.vnet_id
}

resource "azurerm_private_endpoint" "redis" {
  tags                = var.tags
  name                = "pe-redis-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.private_endpoint_subnet_id

  private_service_connection {
    name                           = "psc-redis-${var.env}"
    private_connection_resource_id = azurerm_redis_cache.main.id
    is_manual_connection           = false
    subresource_names              = ["redisCache"]
  }

  private_dns_zone_group {
    name                 = "redis-zone-group"
    private_dns_zone_ids = [azurerm_private_dns_zone.redis.id]
  }
}

# There is no managed identity for Redis's data plane the way blob and Key Vault have one, so the
# access key is the only way in - stored as a secret rather than handed to api_app as a Terraform
# variable, the same module-owns-its-secret pattern modules/postgres uses for POSTGRES-PASSWORD.
resource "azurerm_key_vault_secret" "redis_access_key" {
  tags         = var.tags
  name         = "REDIS-ACCESS-KEY"
  value        = azurerm_redis_cache.main.primary_access_key
  content_type = "Azure Cache for Redis primary access key"
  key_vault_id = var.key_vault_id
}
