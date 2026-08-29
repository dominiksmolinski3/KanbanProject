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

variable "key_vault_id" {
  type = string
}

variable "vnet_id" {
  description = "The ID of the virtual network to link the private DNS zone to."
  type        = string
}

variable "sku_name" {
  description = "Compute SKU for the flexible server. Burstable (B_) tiers throttle to their CPU baseline once credits run out, and Azure does not offer high availability on them."
  type        = string
  default     = "B_Standard_B1ms"

  validation {
    condition     = can(regex("^(B|GP|MO)_", var.sku_name))
    error_message = "The sku_name must be a PostgreSQL Flexible Server SKU: B_ (burstable), GP_ (general purpose) or MO_ (memory optimized), e.g. B_Standard_B1ms or GP_Standard_D2ds_v4."
  }
}

variable "storage_mb" {
  description = "Storage allocated to the server. Azure can only grow this, never shrink it, and the server's IOPS ceiling scales with the size."
  type        = number
  default     = 32768

  validation {
    condition     = contains([32768, 65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33553408], var.storage_mb)
    error_message = "The storage_mb must be one of the sizes Azure offers: 32768, 65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33553408."
  }
}

variable "zone" {
  description = "Availability zone hosting the primary. Set to null in regions that have no availability zones."
  type        = string
  default     = "1"
}

variable "high_availability_mode" {
  description = "Run a hot standby: ZoneRedundant (standby in another zone), SameZone, or null for no standby. Requires a GP_ or MO_ sku_name and doubles the compute bill."
  type        = string
  default     = null

  validation {
    condition     = var.high_availability_mode == null || can(regex("^(ZoneRedundant|SameZone)$", var.high_availability_mode))
    error_message = "The high_availability_mode must be \"ZoneRedundant\", \"SameZone\", or null."
  }
}

variable "standby_availability_zone" {
  description = "Availability zone for the high availability standby. Must differ from zone under ZoneRedundant; leave null to let Azure pick."
  type        = string
  default     = null
}

variable "backup_retention_days" {
  description = "Days of point-in-time restore Azure keeps. Left unset the server runs on Azure's 7-day default, which is the whole recovery window for the only copy of the data."
  type        = number
  default     = 7

  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "The backup_retention_days must be between 7 and 35, the range Azure offers for a flexible server."
  }
}

variable "geo_redundant_backup_enabled" {
  description = "Replicate backups to the paired region, so a regional outage is recoverable. Azure fixes this at create time: changing it replaces the server."
  type        = bool
  default     = false
}
