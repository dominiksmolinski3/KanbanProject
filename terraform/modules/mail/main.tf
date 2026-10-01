resource "azurerm_communication_service" "main" {
  tags                = var.tags
  name                = var.communication_service_name
  resource_group_name = var.resource_group_name
  data_location       = var.data_location

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_email_communication_service" "main" {
  tags                = var.tags
  name                = var.email_service_name
  resource_group_name = var.resource_group_name
  data_location       = var.data_location

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_email_communication_service_domain" "azure_managed" {
  tags                             = var.tags
  name                             = "AzureManagedDomain"
  email_service_id                 = azurerm_email_communication_service.main.id
  domain_management                = "AzureManaged"
  user_engagement_tracking_enabled = false

  lifecycle {
    # The domain is a generated GUID subdomain; a new one changes every sender address.
    prevent_destroy = true
  }
}

resource "azurerm_communication_service_email_domain_association" "main" {
  communication_service_id = azurerm_communication_service.main.id
  email_service_domain_id  = azurerm_email_communication_service_domain.azure_managed.id
}
