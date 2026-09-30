output "storage_account_name" {
  value = azurerm_storage_account.dumps.name
}

output "job_name" {
  value = azurerm_container_app_job.pg_dump.name
}
