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

variable "key_vault_uri" {
  type = string
}

variable "key_vault_id" {
  type = string
}

variable "github_repository_owner" {
  description = "GHCR namespace the app image lives in. Must match the account the CD workflow pushes to."
  type        = string
}

variable "ghcr_username" {
  description = "GitHub account used for the image pull. Required when ghcr_token is set."
  type        = string
  default     = ""
}

variable "ghcr_token" {
  description = <<-EOT
    Personal access token with read:packages, used to pull the app image.

    GHCR authenticates with a username and token only — unlike ACR it does not accept an Azure
    managed identity, so the app's user-assigned identity cannot be used for the pull. The token
    is stored in Key Vault and read back through that identity, so it never lands in the
    Container App template in clear text.

    Empty means no registry credentials are configured at all, which only works if the package
    is public. A private package with no credentials fails with ImagePullBackOff.
  EOT
  type        = string
  sensitive   = true
  default     = ""

  validation {
    condition     = var.ghcr_token == "" || var.ghcr_username != ""
    error_message = "Set ghcr_username alongside ghcr_token: GHCR rejects a token presented without an account name."
  }
}

variable "app_image_tag" {
  description = "Container image tag for the app."
  type        = string

  validation {
    condition     = can(regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$", var.app_image_tag))
    error_message = "The app_image_tag must be a valid OCI image tag: 1-128 characters of A-Za-z0-9._- starting with a letter, digit, or underscore. An empty tag renders an unpullable image reference such as \"ghcr.io/<owner>/kanbanproject-app:\"."
  }
}

variable "max_replicas" {
  description = <<-EOT
    Upper bound on app replicas. Defaults to 1, and should stay there until two pieces of
    in-JVM state are moved out:

      * The STOMP broker is registry.enableSimpleBroker("/topic", "/queue") -- in-process. A chat
        message published on one replica never reaches a subscriber on another, so messages are
        lost silently rather than visibly.
      * The auth rate limiter holds its Caffeine buckets per JVM (AuthRateLimitProperties says so
        in its own docs), which multiplies every configured limit by the replica count.

    Ingress also declares no session affinity, which SockJS's XHR fallback transports need.

    Raising this needs an external broker and a shared-state limiter first; until then a higher
    value promises horizontal scaling the app does not have.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.max_replicas >= 1
    error_message = "The max_replicas must be at least 1."
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

variable "ingress_trusted_proxy_count" {
  description = "How many reverse proxies sit in front of the app, counted from the app outwards. Container Apps ingress is one hop; putting Front Door in front of it would make this 2. The app reads the X-Forwarded-For entry this many places from the right and ignores everything to its left, which is the part a client can forge."
  type        = number
  default     = 1

  validation {
    condition     = var.ingress_trusted_proxy_count >= 0 && floor(var.ingress_trusted_proxy_count) == var.ingress_trusted_proxy_count
    error_message = "ingress_trusted_proxy_count must be a non-negative whole number."
  }
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}
