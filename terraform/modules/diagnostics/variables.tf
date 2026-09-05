variable "env" {
  type        = string
  description = "Environment name used for naming."
}

variable "log_analytics_workspace_id" {
  type        = string
  description = "Log Analytics Workspace resource ID."
}

variable "container_app_id" {
  type        = string
  description = "Resource ID of the Azure Container App."
}

variable "container_app_env_id" {
  type        = string
  description = "Resource ID of the Azure Container Apps Environment."
}

variable "resource_group_name" {
  type        = string
  description = "Resource group name for alert resources."
}

variable "alert_email" {
  type        = string
  description = "Email address for alert notifications. Leave empty to disable alert resources."
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}

variable "key_vault_id" {
  type        = string
  description = "Resource ID of the Key Vault. AuditEvent is the log that records who read which secret."
}

variable "postgres_server_id" {
  type        = string
  description = "Resource ID of the PostgreSQL Flexible Server."
}

variable "location" {
  type        = string
  description = "Azure region. Scheduled query rules are regional resources, unlike the metric alerts above them."
}

variable "acs_communication_service_id" {
  type        = string
  description = "Resource ID of the Azure Communication Services resource that carries mail (portal -> the resource -> JSON view -> id), created by hand outside Terraform - see acs_email_connection_string at the root. Empty (the default) skips both the diagnostic setting and the bounce alert below: there is no resource to point either at yet."
  default     = ""
}
