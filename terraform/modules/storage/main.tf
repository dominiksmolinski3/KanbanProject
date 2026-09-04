resource "random_string" "suffix" {
  length  = 6
  upper   = false
  lower   = true
  numeric = true
  special = false
}

resource "azurerm_storage_account" "attachments" {
  tags                = var.tags
  name                = "stkanban${var.env}${random_string.suffix.result}"
  resource_group_name = var.resource_group_name
  location            = var.location

  account_tier             = "Standard"
  account_kind             = "StorageV2"
  account_replication_type = var.replication_type
  access_tier              = "Hot"

  https_traffic_only_enabled      = true
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  local_user_enabled              = false
  sftp_enabled                    = false
  default_to_oauth_authentication = true

  # Closed to the internet. The application reaches it over the private endpoint below and is the
  # only thing that ever does - a download is streamed through the app rather than fetched by the
  # browser, which is what makes this possible. See terraform/README.md, Attachment storage.
  public_network_access_enabled = false

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
  }

  blob_properties {
    # Long enough to notice a deletion and undo it - and, more importantly, long enough that the
    # database and the blobs can be restored to the same instant. See var.retention_days.
    delete_retention_policy {
      days = var.retention_days
    }

    container_delete_retention_policy {
      days = var.retention_days
    }
  }

  # An hour is longer than any link this application signs - the app asks for five minutes - so this
  # is a ceiling on anything else that ever signs one here, and a record when something does.
  sas_policy {
    expiration_period = "0.01:00:00"
    expiration_action = "Log"
  }

  lifecycle {
    # The name carries a random suffix, so a replacement is a new account and every attachment in
    # the old one becomes unreachable while its rows stay in the database.
    prevent_destroy = true
  }
}

# The app reaches the account over this endpoint; the browser reaches it over the internet, on the
# same hostname. See terraform/README.md, Attachment storage, for how one name resolves to both.
resource "azurerm_private_dns_zone" "blob" {
  tags                = var.tags
  name                = "privatelink.blob.core.windows.net"
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "blob" {
  tags                  = var.tags
  name                  = "${var.env}-blob-vnet-link"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.blob.name
  virtual_network_id    = var.vnet_id
}

resource "azurerm_private_endpoint" "blob" {
  tags                = var.tags
  name                = "pe-blob-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.private_endpoint_subnet_id

  private_service_connection {
    name                           = "psc-blob-${var.env}"
    private_connection_resource_id = azurerm_storage_account.attachments.id
    is_manual_connection           = false
    subresource_names              = ["blob"]
  }

  private_dns_zone_group {
    name                 = "blob-zone-group"
    private_dns_zone_ids = [azurerm_private_dns_zone.blob.id]
  }
}
