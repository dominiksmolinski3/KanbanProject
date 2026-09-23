resource "azurerm_managed_redis" "main" {
  tags                = var.tags
  name                = "redis-kanban-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku_name            = var.sku_name

  public_network_access = "Disabled"

  default_database {
    access_keys_authentication_enabled = true
    client_protocol                    = "Encrypted"
  }
}

resource "azurerm_private_dns_zone" "redis" {
  tags                = var.tags
  name                = "privatelink.redisenterprise.cache.azure.net"
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
    private_connection_resource_id = azurerm_managed_redis.main.id
    is_manual_connection           = false
    subresource_names              = ["redisEnterprise"]
  }

  private_dns_zone_group {
    name                 = "redis-zone-group"
    private_dns_zone_ids = [azurerm_private_dns_zone.redis.id]
  }
}

resource "azurerm_key_vault_secret" "redis_access_key" {
  tags         = var.tags
  name         = "REDIS-ACCESS-KEY"
  value        = azurerm_managed_redis.main.default_database[0].primary_access_key
  content_type = "Azure Managed Redis primary access key"
  key_vault_id = var.key_vault_id
}
