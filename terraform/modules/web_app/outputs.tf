output "container_app_url" {
  description = "Stable public URL of this deployment. The edge is the only app with an external ingress, so this is the origin a browser types, the origin SECURITY_CORS_ALLOWED_ORIGINS has to name, and the origin Event Grid posts delivery reports to."
  value       = "https://${azurerm_container_app.main.ingress[0].fqdn}"
}

output "container_app_id" {
  description = "Resource ID of the edge Container App."
  value       = azurerm_container_app.main.id
}

output "app_name" {
  description = "Name of the edge Container App, so the root module can assert it against the origin the API app is told to allow."
  value       = local.app_name
}

output "api_upstream" {
  description = "The internal address nginx proxies to. Exposed so the root module can check it against the API app's own name rather than leaving two string patterns to agree by hand."
  value       = local.api_upstream
}
