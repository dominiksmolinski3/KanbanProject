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
  default     = "Basic"
  description = "Basic has no SLA and no replica - acceptable for a rate limiter's escalation, which is disposable state the app already fails open when it cannot reach: a lost cache costs a burst of free attempts, not data. Standard or Premium buys an SLA neither this deployment's budget nor this data's value asks for."
}

variable "family" {
  type    = string
  default = "C"
}

variable "capacity" {
  type        = number
  default     = 0
  description = "0 is C0, 250 MB - the smallest and cheapest size Basic offers. A rate limiter's keys are a handful of bytes each with a TTL of at most the longest escalation window, so this is not expected to fill."
}
