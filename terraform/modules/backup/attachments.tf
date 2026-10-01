resource "azurerm_storage_account" "attachment_copy" {
  # checkov:skip=CKV2_AZURE_33: written only by object replication, which needs no endpoint in the VNet
  tags                = var.tags
  name                = "stkattcopy${var.env}${random_string.suffix.result}"
  resource_group_name = azurerm_resource_group.backup.name
  location            = azurerm_resource_group.backup.location

  account_tier             = "Standard"
  account_kind             = "StorageV2"
  account_replication_type = "GRS"
  access_tier              = "Cool"

  https_traffic_only_enabled      = true
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  local_user_enabled              = false
  sftp_enabled                    = false
  default_to_oauth_authentication = true

  public_network_access = "Enabled"

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
  }

  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = var.attachment_retention_days
    }

    container_delete_retention_policy {
      days = var.attachment_retention_days
    }
  }

  sas_policy {
    expiration_period = "0.01:00:00"
    expiration_action = "Log"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_management_lock" "attachment_copy" {
  name       = "lock-${azurerm_storage_account.attachment_copy.name}"
  scope      = azurerm_storage_account.attachment_copy.id
  lock_level = "CanNotDelete"
  notes      = "The only copy of the attachments outside their own region."
}

resource "azurerm_monitor_diagnostic_setting" "attachment_copy" {
  name                       = "diag-kanban-attachment-copy-${var.env}"
  target_resource_id         = "${azurerm_storage_account.attachment_copy.id}/blobServices/default"
  log_analytics_workspace_id = var.log_analytics_workspace_id

  enabled_log {
    category = "StorageRead"
  }
  enabled_log {
    category = "StorageWrite"
  }
  enabled_log {
    category = "StorageDelete"
  }
}

resource "azurerm_storage_container" "attachment_copy" {
  # checkov:skip=CKV2_AZURE_21: the check wants classic storage insights, which needs the account key; the diagnostic setting above logs reads instead
  name                  = var.attachment_container_name
  storage_account_id    = azurerm_storage_account.attachment_copy.id
  container_access_type = "private"
}

resource "azurerm_storage_management_policy" "attachment_copy" {
  storage_account_id = azurerm_storage_account.attachment_copy.id

  rule {
    name    = "expire-previous-versions"
    enabled = true

    filters {
      blob_types = ["blockBlob"]
    }

    actions {
      version {
        delete_after_days_since_creation = var.attachment_retention_days
      }
    }
  }
}

resource "azurerm_storage_object_replication" "attachments" {
  source_storage_account_id      = var.attachment_account_id
  destination_storage_account_id = azurerm_storage_account.attachment_copy.id

  rules {
    source_container_name      = var.attachment_container_name
    destination_container_name = azurerm_storage_container.attachment_copy.name
    copy_blobs_created_after   = "Everything"
  }
}
