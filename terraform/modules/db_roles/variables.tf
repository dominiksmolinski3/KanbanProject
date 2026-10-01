variable "env" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}

variable "container_app_env_id" {
  type = string
}

variable "key_vault_id" {
  type = string
}

variable "key_vault_uri" {
  type = string
}

variable "tenant_id" {
  type = string
}

variable "postgres_server_name" {
  type = string
}

variable "postgres_fqdn" {
  type = string
}

variable "postgres_database" {
  type = string
}

variable "owner" {
  description = "The managed identity the migration job runs as. It joins kanban_owner, which owns the schema."
  type = object({
    name         = string
    principal_id = string
  })
}

variable "writer" {
  description = "The managed identity the API runs as. It joins kanban_writer, which can read and write rows and change nothing else."
  type = object({
    name         = string
    principal_id = string
  })
}

variable "reader" {
  description = "The managed identity the nightly dump logs in as. It joins kanban_reader, which can only SELECT."
  type = object({
    name         = string
    principal_id = string
  })
}

variable "rbac_propagation_delay" {
  type = string
}

variable "password_login_enabled" {
  description = "Whether the server still accepts the psqladmin password. Only the one-time hand-over of tables psqladmin created needs it."
  type        = bool
}
