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

variable "container_app_env_default_domain" {
  description = "The environment's default_domain output. Combined with the API app's own name pattern it gives that app's internal ingress FQDN before either app exists, which is what lets this module address the upstream without a dependency on the other module's resources."
  type        = string
}

variable "key_vault_uri" {
  type = string
}

variable "key_vault_id" {
  type = string
}

variable "github_repository_owner" {
  description = "GHCR namespace the web image lives in. Must match the account the CD workflow pushes to."
  type        = string
}

variable "ghcr_username" {
  description = "GitHub account used for the image pull. Required when ghcr_token is set."
  type        = string
  default     = ""
}

variable "ghcr_token" {
  description = <<-EOT
    Personal access token with read:packages, used to pull the web image.

    The same token the API app uses, and read through a different identity: this one is granted
    Key Vault Secrets User on the GHCR-TOKEN secret alone, so an nginx container cannot read the
    Postgres password or the JWT signing key.

    Empty means no registry credentials at all, which only works if the package is public.
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
  description = "Container image tag for the web image. Deliberately the same variable that tags the API image: the bundle and the API it calls used to be one artifact and could not disagree, and one tag feeding both apps is what replaces that guarantee. Splitting it is the change that makes skew possible."
  type        = string

  validation {
    condition     = can(regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$", var.app_image_tag))
    error_message = "The app_image_tag must be a valid OCI image tag: 1-128 characters of A-Za-z0-9._- starting with a letter, digit, or underscore. An empty tag renders an unpullable image reference such as \"ghcr.io/<owner>/kanbanproject-web:\"."
  }
}

variable "max_replicas" {
  description = <<-EOT
    Upper bound on edge replicas, and the one ceiling in this deployment that is free to move.

    nginx serving files from its own image holds no state at all: no broker, no rate-limit buckets,
    no scheduler, no semaphore. Every reason api_max_replicas is pinned at 1 is a reason about the
    JVM, and none of them reaches this container.

    What it does not buy is API throughput. All /api traffic passes through here, so this is a
    ceiling on the API rather than a way past one - the API app's own ceiling is the binding
    constraint until the state named in its variable has moved out.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.max_replicas >= 1
    error_message = "The max_replicas must be at least 1."
  }
}

variable "allowed_ingress_cidrs" {
  description = "IPv4 CIDR ranges allowed to reach this app's ingress. Empty leaves it open to the internet. This is the public edge, so these live here rather than on the API app, which has no public ingress to restrict."
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

variable "rbac_propagation_delay" {
  description = "How long to wait after granting a role before using it. An RBAC assignment is accepted by ARM before it is usable at the data plane, and Terraform has no primitive that waits for the difference."
  type        = string
  default     = "60s"
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}
