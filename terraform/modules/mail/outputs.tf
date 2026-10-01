output "id" {
  value = azurerm_communication_service.main.id
}

output "connection_string" {
  value     = azurerm_communication_service.main.primary_connection_string
  sensitive = true
}

output "sender_address" {
  # DoNotReply is the sender Azure creates with every Azure-managed domain.
  value = "DoNotReply@${azurerm_email_communication_service_domain.azure_managed.mail_from_sender_domain}"
}
