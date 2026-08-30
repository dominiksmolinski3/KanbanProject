variable "resource_group_name" {
  type        = string
  description = "The name of the resource group."
}

variable "location" {
  type        = string
  description = "The Azure region."
}

variable "env" {
  type        = string
  description = "The environment name."
}

variable "log_analytics_workspace_id" {
  type        = string
  description = "Log Analytics Workspace ID for Container Apps environment diagnostics."
  default     = null
}

variable "ingress_source_address_prefixes" {
  type        = list(string)
  description = "Source ranges allowed to reach the container app on 80/443. Defaults to the whole internet, which is what external_enabled = true needs; narrow it to known CIDRs for environments that should not be publicly reachable."
  default     = ["Internet"]

  validation {
    condition     = length(var.ingress_source_address_prefixes) > 0
    error_message = "ingress_source_address_prefixes must list at least one source, otherwise nothing can reach the app."
  }
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}
