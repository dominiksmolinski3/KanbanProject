output "resource_group_name" {
  value = azurerm_resource_group.backup.name
}

output "storage_account_name" {
  value = azurerm_storage_account.dumps.name
}

output "job_name" {
  value = azurerm_container_app_job.pg_dump.name
}

output "restore_drill_job_name" {
  value = azurerm_container_app_job.restore_drill.name
}

output "identity" {
  value = {
    id           = azurerm_user_assigned_identity.job.id
    client_id    = azurerm_user_assigned_identity.job.client_id
    name         = azurerm_user_assigned_identity.job.name
    principal_id = azurerm_user_assigned_identity.job.principal_id
  }
}
