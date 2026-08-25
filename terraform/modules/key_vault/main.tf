data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "main" {
  name                            = "kv-${var.env}-kanban"
  location                        = var.location
  resource_group_name             = var.resource_group_name
  tenant_id                       = data.azurerm_client_config.current.tenant_id
  sku_name                        = "standard"
  enabled_for_deployment          = true
  enabled_for_disk_encryption     = true
  enabled_for_template_deployment = true

  enable_rbac_authorization = true

  network_acls {
    default_action             = var.network_default_action
    bypass                     = var.allow_azure_services_bypass ? "AzureServices" : "None"
    virtual_network_subnet_ids = [var.subnet_id]
    ip_rules                   = var.ip_rules
  }
}

resource "azurerm_role_assignment" "terraform_caller_secrets_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "time_sleep" "wait_for_secrets_officer" {
  depends_on      = [azurerm_role_assignment.terraform_caller_secrets_officer]
  create_duration = "60s"
}

resource "azurerm_private_dns_zone" "kv" {
  name                = "privatelink.vaultcore.azure.net"
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "kv" {
  name                  = "${var.env}-kv-vnet-link"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.kv.name
  virtual_network_id    = var.vnet_id
}

resource "azurerm_private_endpoint" "kv" {
  name                = "pe-kv-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.subnet_id

  private_service_connection {
    name                           = "psc-kv-${var.env}"
    private_connection_resource_id = azurerm_key_vault.main.id
    is_manual_connection           = false
    subresource_names              = ["vault"]
  }

  private_dns_zone_group {
    name                 = "kv-zone-group"
    private_dns_zone_ids = [azurerm_private_dns_zone.kv.id]
  }
}
