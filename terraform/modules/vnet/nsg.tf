resource "azurerm_network_security_group" "backend" {
  tags                = var.tags
  name                = "nsg-backend-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name

  security_rule {
    name                    = "AllowHttpsInbound"
    priority                = 100
    direction               = "Inbound"
    access                  = "Allow"
    protocol                = "Tcp"
    source_port_range       = "*"
    destination_port_ranges = ["80", "443"]

    source_address_prefix   = length(var.ingress_source_address_prefixes) == 1 ? one(var.ingress_source_address_prefixes) : null
    source_address_prefixes = length(var.ingress_source_address_prefixes) == 1 ? null : var.ingress_source_address_prefixes

    destination_address_prefix = local.backend_subnet_cidr
  }

  security_rule {
    name                       = "AllowIntraSubnetInbound"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = local.backend_subnet_cidr
    destination_address_prefix = local.backend_subnet_cidr
  }

  security_rule {
    name                       = "AllowLoadBalancerProbesInbound"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    source_address_prefix      = "AzureLoadBalancer"
    destination_port_range     = "30000-32767"
    destination_address_prefix = local.backend_subnet_cidr
  }

  security_rule {
    name                       = "DenyVnetInbound"
    priority                   = 4096
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_security_group" "db" {
  tags                = var.tags
  name                = "nsg-db-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name

  security_rule {
    name                       = "AllowPostgresFromBackend"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    source_address_prefix      = local.backend_subnet_cidr
    destination_port_range     = "5432"
    destination_address_prefix = local.db_subnet_cidr
  }

  security_rule {
    name                       = "AllowIntraSubnetInbound"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = local.db_subnet_cidr
    destination_address_prefix = local.db_subnet_cidr
  }

  security_rule {
    name                       = "DenyVnetInbound"
    priority                   = 4096
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_security_group" "private_endpoints" {
  tags                = var.tags
  name                = "nsg-pe-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name

  security_rule {
    name                       = "AllowKeyVaultFromBackend"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    source_address_prefix      = local.backend_subnet_cidr
    destination_port_range     = "443"
    destination_address_prefix = local.pe_subnet_cidr
  }

  security_rule {
    name                       = "DenyVnetInbound"
    priority                   = 4096
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "backend" {
  subnet_id                 = azurerm_subnet.backend.id
  network_security_group_id = azurerm_network_security_group.backend.id
}

resource "azurerm_subnet_network_security_group_association" "db" {
  subnet_id                 = azurerm_subnet.db.id
  network_security_group_id = azurerm_network_security_group.db.id
}

resource "azurerm_subnet_network_security_group_association" "private_endpoints" {
  subnet_id                 = azurerm_subnet.private_endpoints.id
  network_security_group_id = azurerm_network_security_group.private_endpoints.id
}
