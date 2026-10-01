output "storage_account_name" {
  value = azurerm_storage_account.dumps.name
}

output "job_name" {
  value = azurerm_container_app_job.pg_dump.name
}

output "restore_drill_job_name" {
  value = azurerm_container_app_job.restore_drill.name
}
