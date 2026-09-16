output "app_name" {
  description = "Name of the broker Container App - what api_app dials for STOMP_RELAY_HOST. Internal TCP ingress is reachable from other apps in the same environment by name and exposed port, with no FQDN needed."
  value       = local.app_name
}

output "port" {
  description = "The STOMP port other apps in the environment reach this one on."
  value       = local.app_port
}

output "username" {
  description = "The one account WebSocketConfig's client and system logins both use. Not a secret - RABBITMQ-PASSWORD is."
  value       = local.username
}
