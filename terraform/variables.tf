variable "resource_group_name" {
  type        = string
  description = "The name of the resource group."
}

variable "subscription_id" {
  type        = string
  nullable    = true
  description = "Optional Azure subscription ID used by the azurerm provider. Leave unset to use the active Azure CLI subscription (az account show)."
  default     = null
}

variable "location" {
  type        = string
  description = "The Azure region where resources will be deployed."
}

variable "env" {
  type        = string
  description = "The environment name (e.g., dev, prod)."
}

variable "github_repository_owner" {
  type        = string
  description = "The owner of the GitHub repository."
}

variable "app_image_tag" {
  type        = string
  description = "Tag for the application container image. Use an immutable value like a git SHA for reproducible deploys."
  default     = "latest"

  validation {
    condition     = can(regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$", var.app_image_tag))
    error_message = "The app_image_tag must be a valid OCI image tag: 1-128 characters of A-Za-z0-9._- starting with a letter, digit, or underscore. An empty tag renders an unpullable image reference such as \"ghcr.io/<owner>/kanbanproject-app:\"."
  }
}

variable "key_vault_allowed_ips" {
  description = "Optional list of IPv4 addresses allowed to access Key Vault (dev convenience)."
  type        = list(string)
  default     = []
}

variable "key_vault_allow_azure_services_bypass" {
  description = "Allow trusted Azure services to bypass Key Vault firewall."
  type        = bool
  default     = true
}

variable "key_vault_network_default_action" {
  description = "Key Vault network ACL default action: Allow or Deny. For dev you may set Allow."
  type        = string
  default     = "Deny"
}

variable "spring_mail_username" {
  description = "SMTP username for Spring Mail."
  type        = string
  sensitive   = true
}

variable "spring_mail_password" {
  description = "SMTP password for Spring Mail."
  type        = string
  sensitive   = true
}

variable "captcha_enabled" {
  description = "Enable captcha verification in backend."
  type        = bool
  default     = true
}

variable "captcha_secret" {
  description = "Captcha secret used by the backend (server-side verification)."
  type        = string
  sensitive   = true
  default     = ""
}

variable "vite_recaptcha_site_key" {
  description = "Frontend reCAPTCHA site key (Vite env)."
  type        = string
  default     = ""
}

variable "alert_email" {
  description = "Optional email address to receive Azure Monitor alerts. If empty, alerts are not created."
  type        = string
  default     = ""
}
variable "postgres_sku_name" {
  description = "Compute SKU for the Postgres flexible server. Burstable (B_) tiers throttle once CPU credits run out and cannot run high availability."
  type        = string
  default     = "B_Standard_B1ms"
}

variable "postgres_storage_mb" {
  description = "Storage allocated to the Postgres flexible server. Azure can only grow this, never shrink it."
  type        = number
  default     = 32768
}

variable "postgres_zone" {
  description = "Availability zone hosting the Postgres primary. Set to null in regions without availability zones."
  type        = string
  default     = "1"
}

variable "postgres_high_availability_mode" {
  description = "Postgres high availability: ZoneRedundant, SameZone, or null for no standby. Requires a GP_ or MO_ postgres_sku_name."
  type        = string
  default     = null
}

variable "postgres_standby_availability_zone" {
  description = "Availability zone for the Postgres high availability standby. Must differ from postgres_zone under ZoneRedundant; null lets Azure pick."
  type        = string
  default     = null
}
