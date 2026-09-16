# Azure Managed Redis, not the classic Cache for Redis this module shipped with first: the first
# apply against dev was refused outright - "Azure Cache for Redis is retiring, create Azure Managed
# Redis instance instead" - on a subscription that had never created either kind before. Managed
# Redis is Redis Enterprise underneath (still Microsoft.Cache, so providers.tf needed no new
# namespace), which is why the shapes below differ from a classic cache in several places at once:
# there is no top-level access-key/TLS toggle, because those live on default_database instead, and
# the private endpoint's subresource and DNS zone both name "redisEnterprise" rather than "redis".
resource "azurerm_managed_redis" "main" {
  tags                = var.tags
  name                = "redis-kanban-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku_name            = var.sku_name

  # Closed to the internet, the same posture as the blob storage account and Postgres: the app
  # reaches it over the private endpoint below and is the only thing that ever does.
  public_network_access = "Disabled"

  default_database {
    access_keys_authentication_enabled = true
    # Encrypted is the default and is named here anyway: this is the one setting standing in for
    # minimum_tls_version on a classic cache, and a future edit should have to change it on purpose.
    client_protocol = "Encrypted"
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

# There is no managed identity for Redis's data plane the way blob and Key Vault have one, so the
# access key is the only way in - stored as a secret rather than handed to api_app as a Terraform
# variable, the same module-owns-its-secret pattern modules/postgres uses for POSTGRES-PASSWORD.
resource "azurerm_key_vault_secret" "redis_access_key" {
  tags         = var.tags
  name         = "REDIS-ACCESS-KEY"
  value        = azurerm_managed_redis.main.default_database[0].primary_access_key
  content_type = "Azure Managed Redis primary access key"
  key_vault_id = var.key_vault_id
}
