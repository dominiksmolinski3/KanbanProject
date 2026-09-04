variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "env" {
  type = string
}

variable "replication_type" {
  description = <<-EOT
    How the attachment blobs are replicated. GRS keeps a copy in the paired region, which is what a
    production environment holding somebody's files should have; dev and uat set LRS in their tfvars
    because the copies cost money and nothing there is worth restoring.

    Unlike the Postgres geo-redundancy flag next door, Azure lets this be changed after the fact, so
    it is not a decision that has to be right before the account holds anything.
  EOT
  type        = string
  default     = "GRS"

  validation {
    condition     = contains(["LRS", "ZRS", "GRS", "RAGRS", "GZRS", "RAGZRS"], var.replication_type)
    error_message = "The replication_type must be one of LRS, ZRS, GRS, RAGRS, GZRS or RAGZRS."
  }
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}

variable "vnet_id" {
  description = "VNet the blob private DNS zone is linked to, so the app resolves the account to its private endpoint."
  type        = string
}

variable "private_endpoint_subnet_id" {
  description = "Subnet the blob private endpoint NIC is placed in."
  type        = string
}

variable "retention_days" {
  description = "How long a deleted blob or container stays recoverable. Set from postgres_backup_retention_days at the root so the two stores can be restored to the same instant - nothing else keeps their recovery windows aligned, and a shorter one here means a restored database holds rows whose blobs were already purged. Azure allows 1-365 days, against a Postgres maximum of 35."
  type        = number

  validation {
    condition     = var.retention_days >= 1 && var.retention_days <= 365 && floor(var.retention_days) == var.retention_days
    error_message = "The retention_days must be a whole number of days between 1 and 365, which is the range Azure accepts for blob soft delete."
  }
}
