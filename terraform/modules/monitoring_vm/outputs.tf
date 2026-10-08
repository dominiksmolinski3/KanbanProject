output "private_ip" {
  value = local.private_ip
}

output "vm_name" {
  value = azurerm_linux_virtual_machine.main.name
}

output "vm_id" {
  value = azurerm_linux_virtual_machine.main.id
}

output "identity_client_id" {
  value = azurerm_user_assigned_identity.main.client_id
}
