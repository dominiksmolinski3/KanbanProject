variable "resource_group_name" {
  type = string
}

variable "resource_group_id" {
  type = string
}

variable "location" {
  type = string
}

variable "env" {
  type = string
}

variable "tenant_id" {
  type = string
}

variable "subscription_id" {
  type = string
}

variable "container_app_env_id" {
  type = string
}

variable "container_app_env_default_domain" {
  type = string
}

variable "custom_domain" {
  description = "A hostname bound to the app, such as grafana.kanbanproject.pl. Null serves Grafana on its default address."
  type        = string
  default     = null
}

variable "image" {
  type = string
}

variable "prometheus_url" {
  type = string
}

variable "read_password_secret_name" {
  type = string
}

variable "ca_certificate_secret_name" {
  type = string
}

variable "key_vault_id" {
  type = string
}

variable "key_vault_uri" {
  type = string
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "ghcr_username" {
  type    = string
  default = ""
}

variable "ghcr_token" {
  type      = string
  sensitive = true
  default   = ""
}

variable "rbac_propagation_delay" {
  type    = string
  default = "60s"
}

variable "tags" {
  type = map(string)
}
