output "container_app_id" {
  description = "Resource ID of the API Container App."
  value       = azurerm_container_app.main.id
}

output "app_name" {
  description = "Name of the API Container App. The edge composes its upstream address from the same pattern, and the root module asserts that the two agree."
  value       = local.app_name
}

output "internal_fqdn" {
  description = "The environment-internal ingress FQDN. Nothing outside the Managed Environment resolves it, which is why there is deliberately no public URL output here any more - the edge holds the only public address this deployment has."
  value       = azurerm_container_app.main.ingress[0].fqdn
}

output "cpu_cores" {
  description = "CPU limit of one API replica, so alerts can be set as a share of it."
  value       = local.cpu_cores
}

output "memory_bytes" {
  description = "Memory limit of one API replica in bytes, so alerts can be set as a share of it."
  value       = local.memory_gib * 1073741824
}

output "identity" {
  value = {
    name         = azurerm_user_assigned_identity.main.name
    principal_id = azurerm_user_assigned_identity.main.principal_id
  }
}

output "migrator_identity" {
  value = {
    name         = azurerm_user_assigned_identity.migrator.name
    principal_id = azurerm_user_assigned_identity.migrator.principal_id
  }
}
