locals {
  tags = {
    application = "kanban"
    environment = "shared"
    managed_by  = "terraform"
    owner       = var.owner_tag
    repository  = var.github_repository
  }
}

data "azurerm_subscription" "current" {}

data "azurerm_client_config" "current" {}

# Read, not managed: it sits in West Europe, which the subscription's location policy denies.
data "azurerm_resource_group" "state" {
  name = "tfstate-rg"
}

resource "azurerm_storage_account" "state" {
  # checkov:skip=CKV2_AZURE_33: GitHub runners and workstations reach it from addresses nothing can list; Entra auth only
  # checkov:skip=CKV2_KANBAN_1: same reason; shared keys are off, so the network is not the control
  # checkov:skip=CKV_AZURE_206: Poland Central has no geo pair, so ZRS is the most it can have
  name                             = "tfstatekanban"
  resource_group_name              = data.azurerm_resource_group.state.name
  location                         = "polandcentral"
  account_kind                     = "StorageV2"
  account_tier                     = "Standard"
  account_replication_type         = "ZRS"
  min_tls_version                  = "TLS1_2"
  https_traffic_only_enabled       = true
  shared_access_key_enabled        = false
  allow_nested_items_to_be_public  = false
  cross_tenant_replication_enabled = false
  default_to_oauth_authentication  = true

  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 30
    }

    container_delete_retention_policy {
      days = 30
    }
  }

  tags = local.tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_storage_container" "state" {
  # checkov:skip=CKV2_AZURE_21: classic storage logging needs the account key, which is disabled
  name                  = "tfstate"
  storage_account_id    = azurerm_storage_account.state.id
  container_access_type = "private"

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_management_lock" "state" {
  name       = "state-cannot-delete"
  scope      = azurerm_storage_account.state.id
  lock_level = "CanNotDelete"
  notes      = "Holds every environment's Terraform state."
}
