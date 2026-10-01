resource "random_password" "password" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "random_string" "suffix" {
  length  = 5
  upper   = false
  lower   = true
  numeric = true
  special = false
}

data "azurerm_client_config" "current" {}

resource "azurerm_postgresql_flexible_server" "main" {
  tags                          = var.tags
  name                          = "psql-${var.env}-${random_string.suffix.result}"
  resource_group_name           = var.resource_group_name
  location                      = var.location
  version                       = "17"
  public_network_access_enabled = false
  delegated_subnet_id           = var.subnet_id
  private_dns_zone_id           = azurerm_private_dns_zone.main.id
  administrator_login           = "psqladmin"
  administrator_password        = random_password.password.result
  zone                          = var.zone
  storage_mb                    = var.storage_mb
  sku_name                      = var.sku_name
  backup_retention_days         = var.backup_retention_days
  geo_redundant_backup_enabled  = var.geo_redundant_backup_enabled

  authentication {
    active_directory_auth_enabled = true
    password_auth_enabled         = var.password_auth_enabled
    tenant_id                     = data.azurerm_client_config.current.tenant_id
  }

  maintenance_window {
    day_of_week  = 0
    start_hour   = 1
    start_minute = 0
  }

  dynamic "high_availability" {
    for_each = var.high_availability_mode == null ? [] : [var.high_availability_mode]

    content {
      mode                      = high_availability.value
      standby_availability_zone = var.standby_availability_zone
    }
  }

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = var.high_availability_mode == null || !can(regex("^B_", var.sku_name))
      error_message = "High availability is not available on Burstable SKUs. Set sku_name to a GP_ or MO_ SKU, or leave high_availability_mode null."
    }

    precondition {
      condition     = var.high_availability_mode != "ZoneRedundant" || var.standby_availability_zone == null || var.standby_availability_zone != var.zone
      error_message = "A ZoneRedundant standby must sit in a different zone than the primary. Set standby_availability_zone to something other than zone, or leave it null to let Azure pick."
    }
  }
}

resource "azurerm_management_lock" "server" {
  name       = "lock-${azurerm_postgresql_flexible_server.main.name}"
  scope      = azurerm_postgresql_flexible_server.main.id
  lock_level = "CanNotDelete"
  notes      = "prevent_destroy only stops Terraform; this stops the portal and the CLI. Remove the lock first to delete the server on purpose."
}

resource "azurerm_postgresql_flexible_server_database" "main" {
  name      = "kanban"
  server_id = azurerm_postgresql_flexible_server.main.id
  collation = "en_US.utf8"
  charset   = "utf8"
}

resource "azurerm_private_dns_zone" "main" {
  tags                = var.tags
  name                = "privatelink.postgres.database.azure.com"
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "main" {
  tags                = var.tags
  name                = "${var.env}-dns-vnet-link"
  private_dns_zone_id = azurerm_private_dns_zone.main.id
  virtual_network_id  = var.vnet_id
}

resource "azurerm_key_vault_secret" "postgres_user" {
  tags         = var.tags
  name         = "POSTGRES-USER"
  value        = azurerm_postgresql_flexible_server.main.administrator_login
  content_type = "PostgreSQL administrator login"
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "postgres_password" {
  tags         = var.tags
  name         = "POSTGRES-PASSWORD"
  value        = random_password.password.result
  content_type = "PostgreSQL administrator password"
  key_vault_id = var.key_vault_id
}

locals {
  max_connections_by_sku = {
    "B_Standard_B1ms"     = 50
    "B_Standard_B2s"      = 429
    "GP_Standard_D2ds_v4" = 859
    "GP_Standard_D4ds_v4" = 1719
  }

  superuser_reserved_connections = 10
}
