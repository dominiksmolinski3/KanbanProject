variable "env" {
  type = string
}

variable "location" {
  description = "The database's region. A point-in-time restore lands beside its source, so the scratch group lives here too."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}

variable "resource_group_name" {
  description = "The application's resource group, which holds the job, its identity, its alert and the source server."
  type        = string
}

variable "resource_group_id" {
  type = string
}

variable "container_app_env_id" {
  type = string
}

variable "postgres_server_id" {
  type = string
}

variable "postgres_subnet_id" {
  description = "The delegated subnet the source sits in. The restored copy inherits it, and creating a server there needs join on it."
  type        = string
}

variable "postgres_dns_zone_id" {
  type = string
}

variable "postgres_database" {
  type = string
}

variable "reader_identity" {
  description = "An identity that is already a read-only login on the source database, so it is one on every copy restored from it."
  type = object({
    id        = string
    client_id = string
    name      = string
  })
}

variable "cron_expression" {
  description = "When the drill runs, in UTC. Monthly, away from the nightly dump and dump drill."
  type        = string
  default     = "45 4 2 * *"
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
