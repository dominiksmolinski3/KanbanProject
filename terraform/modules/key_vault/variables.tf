variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "env" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "vnet_id" {
  description = "The ID of the virtual network for private DNS zone linking."
  type        = string
}

variable "ip_rules" {
  description = "Optional list of public IPv4 addresses allowed to access the Key Vault (for dev)."
  type        = list(string)
  default     = []
}

variable "allow_azure_services_bypass" {
  description = "Whether to allow trusted Azure services to bypass the firewall."
  type        = bool
  default     = true
}

variable "network_default_action" {
  description = "Key Vault network ACL default action: Allow or Deny."
  type        = string
  default     = "Deny"
  validation {
    condition     = contains(["Allow", "Deny"], var.network_default_action)
    error_message = "network_default_action must be either 'Allow' or 'Deny'."
  }
}

variable "purge_protection_enabled" {
  description = "Block permanent deletion of the vault. Azure does not allow turning this back off once an apply has enabled it."
  type        = bool
  default     = true
}

variable "soft_delete_retention_days" {
  description = "Days a deleted vault stays recoverable, and for which its name stays reserved. Changing this replaces the vault."
  type        = number
  default     = 90
  validation {
    condition     = var.soft_delete_retention_days >= 7 && var.soft_delete_retention_days <= 90
    error_message = "soft_delete_retention_days must be between 7 and 90."
  }
}
