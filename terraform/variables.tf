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
  description = "Owner of the GHCR namespace the app image is pulled from. This has to be the account kanban-cd.yml pushes to, and the workflow publishes under the repository owner — so on a fork it is the fork's owner, not the upstream's. Pointing it elsewhere plans cleanly and then fails at pull time."
}

variable "ghcr_username" {
  type        = string
  description = "GitHub account used to authenticate the image pull from GHCR. Required when ghcr_token is set. Leave empty only if the package is public — GHCR does not accept an Azure managed identity, so a private package needs a token here."
  default     = ""
}

variable "ghcr_token" {
  type        = string
  sensitive   = true
  description = "Personal access token with read:packages, stored in Key Vault and read by the Container App. Empty means the package must be public, or the pull fails with ImagePullBackOff and no way to authenticate."
  default     = ""
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

variable "max_replicas" {
  description = "Upper bound on app replicas. Keep at 1: the STOMP broker and the auth rate limiter both hold state in the JVM, so a second replica loses chat messages and multiplies every rate limit. See the module variable of the same name."
  type        = number
  default     = 1

  validation {
    condition     = var.max_replicas >= 1
    error_message = "The max_replicas must be at least 1."
  }
}

variable "ingress_source_address_prefixes" {
  description = "Source ranges the backend subnet NSG allows to reach the container app on 80/443. Leave at Internet for a publicly reachable environment; narrow to office/CI CIDRs otherwise."
  type        = list(string)
  default     = ["Internet"]
}

variable "key_vault_allowed_ips" {
  description = <<-EOT
    Public IPv4 addresses or CIDR ranges allowed through the Key Vault firewall, in addition
    to the backend subnet. Terraform writes secrets over the data plane, so whoever runs
    `terraform apply` from outside the VNet has to appear in this list. Record stable egress
    addresses here (a self-hosted runner, an office range); for local development set your own
    address in the gitignored dev.local.auto.tfvars rather than committing it.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for ip in var.key_vault_allowed_ips :
      can(cidrnetmask(strcontains(ip, "/") ? ip : "${ip}/32"))
    ])
    error_message = "Every key_vault_allowed_ips entry must be an IPv4 address (203.0.113.42) or CIDR range (203.0.113.0/24). Azure rejects the whole ACL if one entry is malformed."
  }
}

variable "key_vault_allow_azure_services_bypass" {
  description = "Allow trusted Azure services to bypass Key Vault firewall."
  type        = bool
  default     = true
}

variable "key_vault_purge_protection_enabled" {
  description = "Block permanent deletion of the Key Vault. Once an apply enables this, Azure will not let any later apply turn it off."
  type        = bool
  default     = true
}

variable "key_vault_soft_delete_retention_days" {
  description = "Days a deleted Key Vault stays recoverable and its name stays reserved. Azure treats this as immutable, so changing it replaces the vault."
  type        = number
  default     = 90
}

variable "key_vault_purge_soft_delete_on_destroy" {
  description = "Whether terraform destroy permanently purges the Key Vault instead of leaving it soft-deleted. Only meaningful when key_vault_purge_protection_enabled is false."
  type        = bool
  default     = false
}

variable "key_vault_network_default_action" {
  description = "Key Vault network ACL default action: Allow or Deny. For dev you may set Allow."
  type        = string
  default     = "Deny"
}

variable "allowed_ingress_cidrs" {
  description = "IPv4 CIDR ranges allowed to reach the Container App ingress, e.g. an office address as \"203.0.113.42/32\". Intended for locking non-prod environments to the team; unusable on an environment with real users. Empty (the default) leaves ingress open to the internet; any entry turns it into an allow-list and denies everything else."
  type        = list(string)
  default     = []
}

variable "acs_email_connection_string" {
  description = "Connection string for the Azure Communication Services resource that carries mail (portal -> the resource -> Keys). Empty turns mail off: the app starts and drops messages instead of refusing to boot."
  type        = string
  sensitive   = true
  default     = ""
}

variable "acs_email_sender_address" {
  description = "MailFrom address on a domain linked to the Communication Services resource, e.g. \"DoNotReply@<guid>.azurecomm.net\". Not a secret."
  type        = string
  default     = ""
}

variable "acs_communication_service_id" {
  description = "Resource ID of the Communication Services resource created by hand outside Terraform (portal -> the resource -> JSON view -> id), e.g. \"/subscriptions/<sub>/resourceGroups/<rg>/providers/Microsoft.Communication/communicationServices/<name>\". Not a secret. Empty (the default) means no bounce alert - see the diagnostics module."
  type        = string
  default     = ""
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

variable "attachment_retention_days" {
  description = "How long a deleted attachment blob stays recoverable. Null - the default - ties it to postgres_backup_retention_days, so the database and the blobs can be restored to the same instant; there is no foreign key between the two stores and nothing else keeps their recovery windows aligned. Set a number only to break that tie on purpose. Azure allows 1-365 here, against a Postgres maximum of 35."
  type        = number
  default     = null
}

variable "storage_replication_type" {
  description = "Replication for the task-attachment storage account. GRS keeps a copy in the paired region and is what an environment holding somebody's files should have; dev and uat set LRS. Unlike the Postgres geo-redundancy flag, Azure lets this be changed later."
  type        = string
  default     = "GRS"
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

variable "postgres_backup_retention_days" {
  description = "Days of point-in-time restore kept for the database. 7 (Azure's default) is a week to notice a bad migration; prod should buy more."
  type        = number
  default     = 7
}

variable "postgres_geo_redundant_backup_enabled" {
  description = "Replicate Postgres backups to the paired region. Azure fixes this at create time, so turning it on later replaces the server — decide it before an environment carries real data."
  type        = bool
  default     = false
}

variable "owner_tag" {
  description = "Value of the `owner` tag on every resource - a team or a person accountable for the environment. Cost reports and policy both group on it, so an empty value is worse than a rough one."
  type        = string
  default     = "unassigned"
}

variable "extra_tags" {
  description = "Additional tags merged over the standard set. Use for cost-centre or ticket references that only apply to one environment."
  type        = map(string)
  default     = {}
}

variable "ingress_trusted_proxy_count" {
  description = "How many reverse proxies sit in front of the app, counted from the app outwards. Container Apps ingress is one hop; putting Front Door in front of it makes this 2. The app reads the X-Forwarded-For entry this many places from the right and ignores everything to its left, which is the part a client can forge. Set to 0 to ignore the header entirely."
  type        = number
  default     = 1

  validation {
    condition     = var.ingress_trusted_proxy_count >= 0 && floor(var.ingress_trusted_proxy_count) == var.ingress_trusted_proxy_count
    error_message = "ingress_trusted_proxy_count must be a non-negative whole number."
  }
}
