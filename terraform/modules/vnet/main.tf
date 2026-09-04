locals {
  vnet_address_space  = "10.0.0.0/16"
  backend_subnet_cidr = "10.0.0.0/23"
  db_subnet_cidr      = "10.0.2.0/24"
  pe_subnet_cidr      = "10.0.3.0/28"
  storage_subnet_cidr = "10.0.3.16/28"
}

resource "azurerm_virtual_network" "main" {
  tags                = var.tags
  name                = "vnet-${var.env}"
  address_space       = [local.vnet_address_space]
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "azurerm_subnet" "backend" {
  name                 = "snet-backend-${var.env}"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = [local.backend_subnet_cidr]
  service_endpoints    = ["Microsoft.KeyVault"]
}

resource "azurerm_subnet" "private_endpoints" {
  name                 = "snet-pe-${var.env}"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = [local.pe_subnet_cidr]

  private_endpoint_network_policies = "NetworkSecurityGroupEnabled"
}

# The blob private endpoint, kept apart from snet-pe so each service's reachability is its own NSG
# rule rather than one rule covering both.
resource "azurerm_subnet" "storage" {
  name                 = "snet-storage-${var.env}"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = [local.storage_subnet_cidr]

  private_endpoint_network_policies = "NetworkSecurityGroupEnabled"
}

resource "azurerm_subnet" "db" {
  name                 = "snet-db-${var.env}"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = [local.db_subnet_cidr]
  service_endpoints    = ["Microsoft.Storage"]
  delegation {
    name = "fs"
    service_delegation {
      name = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = [
        "Microsoft.Network/virtualNetworks/subnets/join/action",
      ]
    }
  }
}

resource "azurerm_container_app_environment" "main" {
  tags                       = var.tags
  name                       = "cae-${var.env}"
  location                   = var.location
  resource_group_name        = var.resource_group_name
  infrastructure_subnet_id   = azurerm_subnet.backend.id
  log_analytics_workspace_id = var.log_analytics_workspace_id
}
