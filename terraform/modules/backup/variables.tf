variable "env" {
  type = string
}

variable "location" {
  description = "Region of the backup resource group and account. Deliberately not the database's region, so one regional incident cannot take both."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}

variable "job_resource_group_name" {
  description = "Resource group the dump job, its identity and its alert live in: the application's, beside the database they read."
  type        = string
}

variable "job_location" {
  description = "Region of the Container Apps environment the job runs in."
  type        = string
}

variable "container_app_env_id" {
  type = string
}

variable "writer_subnet_id" {
  description = "Subnet the job runs in. It needs the Microsoft.Storage.Global service endpoint, since the account is in another region and has no private endpoint."
  type        = string
}

variable "key_vault_id" {
  type = string
}

variable "key_vault_uri" {
  type = string
}

variable "postgres_fqdn" {
  type = string
}

variable "postgres_database" {
  type = string
}

variable "cron_expression" {
  description = "When the dump runs, in UTC."
  type        = string
  default     = "15 2 * * *"
}

variable "rbac_propagation_delay" {
  type = string
}

variable "alerts_enabled" {
  type = bool
}

variable "action_group_id" {
  type    = string
  default = null
}

variable "log_analytics_workspace_id" {
  type = string
}
