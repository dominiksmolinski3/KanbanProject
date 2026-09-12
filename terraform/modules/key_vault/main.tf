data "azurerm_client_config" "current" {}

resource "random_string" "suffix" {
  length  = 5
  upper   = false
  lower   = true
  numeric = true
  special = false
}

resource "azurerm_key_vault" "main" {
  tags                = var.tags
  name                = "kv-${var.env}-kanban-${random_string.suffix.result}"
  location            = var.location
  resource_group_name = var.resource_group_name
  tenant_id           = data.azurerm_client_config.current.tenant_id
  sku_name            = "standard"

  enabled_for_deployment          = false
  enabled_for_disk_encryption     = false
  enabled_for_template_deployment = false

  rbac_authorization_enabled = true

  purge_protection_enabled   = var.purge_protection_enabled
  soft_delete_retention_days = var.soft_delete_retention_days

  network_acls {
    default_action             = var.network_default_action
    bypass                     = var.allow_azure_services_bypass ? "AzureServices" : "None"
    virtual_network_subnet_ids = [var.allowed_subnet_id]
    ip_rules                   = var.ip_rules
  }
}

resource "azurerm_role_assignment" "terraform_caller_secrets_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# An RBAC grant is not usable the instant ARM accepts it: the assignment has to reach the data
# plane, and the secrets below are written by the identity granted just above. Terraform has no
# "wait until this permission works" primitive, so this is the community answer - sleep, and hope
# the tenant was not slower than the guess.
#
# `triggers` is the part that was missing, and without it this resource protected exactly one
# apply. `depends_on` orders a create; it does not make a resource that already exists in state
# create again. So the first apply waited and every subsequent one did not - including the applies
# that need it most, where the assignment is *replaced* because the vault was rebuilt or a
# different operator ran it, and the grant is brand new while the wait is a no-op left over from
# months ago. Keying the wait on the assignment's own id means it is recreated exactly when the
# thing it is waiting for is. Referencing that id is also the dependency, which is why there is no
# longer a `depends_on` saying the same thing twice.
resource "time_sleep" "wait_for_secrets_officer" {
  triggers = {
    role_assignment_id = azurerm_role_assignment.terraform_caller_secrets_officer.id
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_private_dns_zone" "kv" {
  tags                = var.tags
  name                = "privatelink.vaultcore.azure.net"
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "kv" {
  tags                = var.tags
  name                = "${var.env}-kv-vnet-link"
  private_dns_zone_id = azurerm_private_dns_zone.kv.id
  virtual_network_id  = var.vnet_id
}

resource "azurerm_private_endpoint" "kv" {
  tags                = var.tags
  name                = "pe-kv-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.private_endpoint_subnet_id

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
