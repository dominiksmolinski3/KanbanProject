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

resource "azurerm_postgresql_flexible_server" "main" {
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

  # Azure only rejects these two combinations once it has the create request, by
  # which point the vnet, the vault and its secrets already exist. Fail the plan.
  lifecycle {
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

resource "azurerm_postgresql_flexible_server_database" "main" {
  name      = "kanban"
  server_id = azurerm_postgresql_flexible_server.main.id
  collation = "en_US.utf8"
  charset   = "utf8"
}

resource "azurerm_private_dns_zone" "main" {
  name                = "privatelink.postgres.database.azure.com"
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "main" {
  name                  = "${var.env}-dns-vnet-link"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.main.name
  virtual_network_id    = var.vnet_id
}

resource "azurerm_key_vault_secret" "postgres_user" {
  name         = "POSTGRES-USER"
  value        = azurerm_postgresql_flexible_server.main.administrator_login
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "postgres_password" {
  name         = "POSTGRES-PASSWORD"
  value        = random_password.password.result
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "postgres_connection_string" {
  name         = "POSTGRES-CONNECTION-STRING"
  value        = format("jdbc:postgresql://%s:5432/%s?sslmode=require", azurerm_postgresql_flexible_server.main.fqdn, azurerm_postgresql_flexible_server_database.main.name)
  key_vault_id = var.key_vault_id
}
