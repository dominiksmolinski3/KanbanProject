variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "env" {
  type = string
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

variable "rbac_propagation_delay" {
  description = "How long to wait after granting a role before using it. An RBAC assignment is accepted by ARM before it is usable at the data plane, and Terraform has no primitive that waits for the difference."
  type        = string
  default     = "60s"
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}
