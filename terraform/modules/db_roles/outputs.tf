output "ready" {
  description = "Changes whenever the roles job has run. Anything that logs in as an Entra role depends on it."
  value       = terraform_data.roles.id
}

