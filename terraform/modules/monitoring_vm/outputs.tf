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

output "read_password_secret_name" {
  value = azurerm_key_vault_secret.read_password.name
}

output "ca_certificate_secret_name" {
  value = azurerm_key_vault_secret.ca_certificate.name
}

output "push_password_secret_name" {
  value = azurerm_key_vault_secret.push_password.name
}

output "identity_principal_id" {
  value = azurerm_user_assigned_identity.main.principal_id
}
