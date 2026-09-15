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
