output "resource_group_name" {
  value = azurerm_resource_group.scratch.name
}

output "job_name" {
  value = azurerm_container_app_job.drill.name
}
