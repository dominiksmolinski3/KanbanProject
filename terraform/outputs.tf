output "container_app_url" {
  description = "The public URL of this deployment - the edge app's ingress, which is the only external one. Keeps its name so DEPLOYED_ORIGIN and anything reading `terraform output` do not have to change with the split."
  value       = module.web_app.container_app_url
}

output "api_internal_fqdn" {
  description = "Where the edge proxies to. Resolvable only from inside the Managed Environment; useful for confirming the API app really has no public address."
  value       = module.api_app.internal_fqdn
}

output "log_analytics_workspace_id" {
  description = "Log Analytics Workspace ID used by the Container Apps environment."
  value       = azurerm_log_analytics_workspace.main.id
}

output "log_analytics_workspace_name" {
  description = "Log Analytics Workspace name used by the Container Apps environment."
  value       = azurerm_log_analytics_workspace.main.name
}
