locals {
  private_ip = cidrhost(var.subnet_cidr, 4)
}

resource "azurerm_user_assigned_identity" "main" {
  tags                = var.tags
  name                = "kanban-monitoring-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "azurerm_network_interface" "main" {
  tags                = var.tags
  name                = "nic-monitoring-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name

  ip_configuration {
    name                          = "internal"
    subnet_id                     = var.subnet_id
    private_ip_address_allocation = "Static"
    private_ip_address            = local.private_ip
  }
}

resource "azurerm_linux_virtual_machine" "main" {
  tags                  = merge(var.tags, { role = "monitoring" })
  name                  = "vm-monitoring-${var.env}"
  computer_name         = "monitoring"
  location              = var.location
  resource_group_name   = var.resource_group_name
  size                  = var.size
  network_interface_ids = [azurerm_network_interface.main.id]

  admin_username                  = "kanban"
  disable_password_authentication = true

  # The private half was discarded: Azure requires a key, and nothing can reach port 22.
  admin_ssh_key {
    username   = "kanban"
    public_key = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDDBHQvpQdSHLCMEA978kBVd6w+uk3orgBO7G2ibwye0tmQbOZ38ztG+a5lQIWP7Ml9tH75iMI5Z3rT5MetSiPY+mlybeJSl5z1Kswgkg34l2d0okG6UzgT0IAX/LzumGaUujBJ0bOtayzyEsYKvRp/NuUuqwDA6vODEPf40590F6hCXo3kMeDVZFH2FWrIuCyU1vQlp+9Yd0zWQ12YLNj6WSi6Lrioj1hMbVC7uuvyAR9P3VWedLr0102NypBrEQMIGXSshuLjVjU6YPuNQNY052+pJzyiMAv1xSWrDGZSwU80lx0gUGe1dIcOTYZ8yxo6209lhrJwilLXzU+BH6PSrkjlPdcnzRDC5v/V4D4ZrclptbcvNtKv8noSsTgObMxxAwcQMzE+G5jTIRR5mBHcG00bSF2ZWwDU6omHiTrkpKgZPZna71tRUFVAFji7MtfxnVyq7fW3e1ZWCrZ30bJ2FGWyBomo/Ju3ImCZlgH40RVYU0VTdwzAgIIyk6NaEX8= monitoring-vm-unused"
  }

  encryption_at_host_enabled = true
  secure_boot_enabled        = true
  vtpm_enabled               = true
  provision_vm_agent         = true
  allow_extension_operations = true

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.main.id]
  }

  os_disk {
    name                 = "osdisk-monitoring-${var.env}"
    caching              = "ReadWrite"
    storage_account_type = "StandardSSD_LRS"
    disk_size_gb         = 30
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }

  boot_diagnostics {}
}

resource "azurerm_managed_disk" "data" {
  tags                 = var.tags
  name                 = "disk-prometheus-${var.env}"
  location             = var.location
  resource_group_name  = var.resource_group_name
  storage_account_type = "StandardSSD_LRS"
  create_option        = "Empty"
  disk_size_gb         = var.data_disk_size_gb

  public_network_access_enabled = false
  network_access_policy         = "DenyAll"
}

resource "azurerm_virtual_machine_data_disk_attachment" "data" {
  managed_disk_id    = azurerm_managed_disk.data.id
  virtual_machine_id = azurerm_linux_virtual_machine.main.id
  lun                = 0
  caching            = "None"
}

ephemeral "random_password" "push" {
  length  = 32
  special = false
}

ephemeral "random_password" "read" {
  length  = 32
  special = false
}

resource "azurerm_key_vault_secret" "push_password" {
  tags             = var.tags
  name             = "MONITORING-PUSH-PASSWORD"
  value_wo         = ephemeral.random_password.push.result
  value_wo_version = var.credentials_version
  content_type     = "Prometheus push credential, user kanban-api"
  key_vault_id     = var.key_vault_id
}

# Written from the same ephemeral value as MONITORING-PUSH-PASSWORD, so both must change in one apply.
resource "azurerm_key_vault_secret" "otlp_auth_header" {
  tags             = var.tags
  name             = "OTLP-AUTH-HEADER"
  value_wo         = "Basic ${base64encode("kanban-api:${ephemeral.random_password.push.result}")}"
  value_wo_version = var.credentials_version
  content_type     = "Authorization header for the API's OTLP push"
  key_vault_id     = var.key_vault_id
}

resource "azurerm_key_vault_secret" "read_password" {
  tags             = var.tags
  name             = "MONITORING-READ-PASSWORD"
  value_wo         = ephemeral.random_password.read.result
  value_wo_version = var.credentials_version
  content_type     = "Prometheus read credential, user grafana"
  key_vault_id     = var.key_vault_id
}

# The VM writes its own CA certificate here; Terraform creates the secret only so a role can be scoped to it.
resource "azurerm_key_vault_secret" "ca_certificate" {
  tags             = var.tags
  name             = "MONITORING-CA-CERT"
  value_wo         = "pending: written by the VM on its first converge"
  value_wo_version = 1
  content_type     = "application/x-pem-file"
  key_vault_id     = var.key_vault_id

  # Tags belong to a secret version, so every version the VM writes arrives without them.
  lifecycle {
    ignore_changes = [tags]
  }
}

resource "azurerm_role_assignment" "push_password_reader" {
  scope                = "${var.key_vault_id}/secrets/${azurerm_key_vault_secret.push_password.name}"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

resource "azurerm_role_assignment" "read_password_reader" {
  scope                = "${var.key_vault_id}/secrets/${azurerm_key_vault_secret.read_password.name}"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

resource "azurerm_role_assignment" "ca_certificate_writer" {
  scope                = "${var.key_vault_id}/secrets/${azurerm_key_vault_secret.ca_certificate.name}"
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

resource "azurerm_role_definition" "operator" {
  name        = "Monitoring VM Operator (${var.env})"
  scope       = var.resource_group_id
  description = "Run commands on the monitoring VM, which is how it is configured, and nothing else."

  permissions {
    actions = [
      "Microsoft.Compute/virtualMachines/read",
      "Microsoft.Compute/virtualMachines/runCommand/action",
      "Microsoft.Compute/locations/operations/read",
    ]
  }

  assignable_scopes = [var.resource_group_id]
}

resource "azurerm_role_assignment" "operator" {
  for_each           = toset(var.operator_object_ids)
  scope              = azurerm_linux_virtual_machine.main.id
  role_definition_id = azurerm_role_definition.operator.role_definition_resource_id
  principal_id       = each.value
}

resource "azurerm_monitor_metric_alert" "unavailable" {
  count               = var.alerts_enabled ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-monitoring-vm-unavailable"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_linux_virtual_machine.main.id]
  description         = "The monitoring VM has been unavailable for 15 minutes. Prometheus cannot report its own death, so Azure watches it."
  severity            = 2
  frequency           = "PT5M"
  window_size         = "PT15M"

  criteria {
    metric_namespace = "Microsoft.Compute/virtualMachines"
    metric_name      = "VmAvailabilityMetric"
    aggregation      = "Average"
    operator         = "LessThan"
    threshold        = 1
  }

  action {
    action_group_id = var.action_group_id
  }
}
