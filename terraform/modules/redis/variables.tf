variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "env" {
  type = string
}

variable "vnet_id" {
  type        = string
  description = "For the private DNS zone's virtual network link."
}

variable "private_endpoint_subnet_id" {
  type        = string
  description = "Shared with Key Vault's private endpoint - a generic-purpose subnet, not one dedicated to Redis, so this module adds no subnet of its own."
}

variable "key_vault_id" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "sku_name" {
  type        = string
  default     = "Balanced_B0"
  description = "The smallest Azure Managed Redis SKU. A rate limiter's escalation is disposable state the app already fails open when it cannot reach - a lost cache costs a burst of free attempts back to callers, not data - so the cheapest tier is not a corner cut, it is what the data is worth. Every SKU family (Balanced/ComputeOptimized/FlashOptimized/MemoryOptimized) starts a size 0; only geo-replication needs Balanced_B3 or above, and this deployment has none."
}
