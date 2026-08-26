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

variable "postgres_server_name" {
  type = string
}

variable "postgres_db_name" {
  type = string
}

variable "key_vault_uri" {
  type = string
}

variable "key_vault_id" {
  type = string
}

variable "github_repository_owner" {
  type = string
}

variable "app_image_tag" {
  description = "Container image tag for the app."
  type        = string

  validation {
    condition     = can(regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$", var.app_image_tag))
    error_message = "The app_image_tag must be a valid OCI image tag: 1-128 characters of A-Za-z0-9._- starting with a letter, digit, or underscore. An empty tag renders an unpullable image reference such as \"ghcr.io/<owner>/kanbanproject-app:\"."
  }
}

variable "spring_mail_username" {
  type      = string
  sensitive = true
}

variable "spring_mail_password" {
  type      = string
  sensitive = true
}

variable "captcha_enabled" {
  type = bool
}

variable "captcha_secret" {
  type      = string
  sensitive = true
}

variable "vite_recaptcha_site_key" {
  type = string
}

variable "allowed_ingress_cidrs" {
  description = "IPv4 CIDR ranges allowed to reach the Container App ingress. Empty leaves ingress open to the internet."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.allowed_ingress_cidrs :
      can(cidrhost(cidr, 0)) && !strcontains(cidr, ":")
    ])
    error_message = "Each allowed_ingress_cidrs entry must be an IPv4 range in CIDR notation (e.g. \"203.0.113.42/32\"). Container Apps ingress restrictions reject bare addresses and do not support IPv6."
  }
}
