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
  description = "The environment's default_domain output. Combined with the web app's name it gives the browser's origin before either app resource exists - which is what lets SECURITY_CORS_ALLOWED_ORIGINS name the edge without a dependency cycle between the two modules."
  type        = string
}

variable "web_app_name" {
  description = "Name of the edge Container App, whose FQDN is the origin a browser actually holds. nginx forwards the browser's Origin header unchanged, so this - not this app's own name - is what Spring's CORS allow-list has to contain. Passed in from the web module's output rather than composed here, so there is one place the name is written."
  type        = string
}

variable "extra_cors_origins" {
  description = "Additional browser origins allowed to call this API, e.g. a production custom domain (\"https://kanbanproject.pl\"). The app's own generated ingress URL is always included; this is for anything served under a different hostname. Empty by default because dev/uat have no custom domain."
  type        = list(string)
  default     = []
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
    Upper bound on API replicas. Defaulted to 1 through phase 3 of the container-split plan, while
    the JVM held state no second replica could see: the outbox relay and the deadline sweep now
    claim their rows with FOR UPDATE SKIP LOCKED, the auth rate limiter's escalation lives in Redis
    (see modules/redis and AuthRateLimiter) rather than each replica's own process memory, and
    WebSocketConfig relays STOMP through a real broker (see modules/broker) instead of holding one
    in-process - a board event or a chat message published on one replica now reaches a subscriber
    on another the same way it would from a single JVM.

    Ingress declares no session affinity, which SockJS's XHR fallback transports need - unaffected
    by this ceiling, since the broker relay is what makes which replica a given subscriber landed
    on stop mattering.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.max_replicas >= 1
    error_message = "The max_replicas must be at least 1."
  }
}

variable "acs_email_connection_string" {
  type      = string
  sensitive = true
}

variable "acs_email_sender_address" {
  type = string
}

variable "captcha_enabled" {
  type = bool
}

variable "captcha_secret" {
  type      = string
  sensitive = true
}

variable "ingress_trusted_proxy_count" {
  description = <<-EOT
    How many reverse proxies sit in front of the app, counted from the app outwards. There are two
    since the split - the Container Apps ingress, then nginx - and this is the easiest thing in the
    whole arrangement to get wrong, because getting it wrong fails silently.

    ClientIpResolver reads the X-Forwarded-For entry this many places from the right and ignores
    everything to its left, which is the part a client can forge. Left at 1 with two proxies in
    front, every request keys on nginx's own pod address: one shared escalation bucket for the
    entire internet, and the per-IP CREDENTIALS limit stops existing. Nothing 500s and nothing logs.

    Set to 0 to ignore the header entirely.
  EOT
  type        = number
  default     = 2

  validation {
    condition     = var.ingress_trusted_proxy_count >= 0 && floor(var.ingress_trusted_proxy_count) == var.ingress_trusted_proxy_count
    error_message = "ingress_trusted_proxy_count must be a non-negative whole number."
  }
}

variable "tags" {
  description = "Tags applied to every resource this module creates. Set once at the root."
  type        = map(string)
}

variable "storage_account_id" {
  description = "Resource id of the attachment storage account. The role assignment that lets the app read and write blobs is made here rather than in the storage module, because the identity it is granted to is created here - and the storage module would otherwise have to depend on this one while this one depends on it for the endpoint."
  type        = string
}

variable "storage_blob_endpoint" {
  description = "Blob service endpoint the app stores task attachments in, e.g. \"https://stkanbanprod123456.blob.core.windows.net/\". Not a secret: it is reached with a token, and the account allows no anonymous access."
  type        = string
}

variable "redis_hostname" {
  description = "Hostname of the Azure Managed Redis instance backing AuthRateLimiter's escalation. Not a secret - the access key is (REDIS-ACCESS-KEY, read from Key Vault by name, the same pattern the Postgres password uses)."
  type        = string
}

variable "redis_port" {
  description = "Port of the default database's encrypted endpoint (10000, not the 6380/6379 a classic Cache for Redis would answer on). The encryption comes from default_database.client_protocol in modules/redis, not from which port this names."
  type        = number
}

variable "broker_app_name" {
  description = "Name of the STOMP broker Container App WebSocketConfig relays to - see modules/broker. Reached by app name rather than an internal FQDN: TCP ingress within one Container Apps environment resolves apps by name and exposed port, with no Host-header routing involved."
  type        = string
}

variable "broker_port" {
  description = "The STOMP port on the broker app - see modules/broker's own port output."
  type        = number
}

variable "broker_username" {
  description = "The one RabbitMQ account WebSocketConfig's client and system logins both use. Not a secret - RABBITMQ-PASSWORD is, read from Key Vault by name the same way POSTGRES-PASSWORD and REDIS-ACCESS-KEY are."
  type        = string
}

variable "mail_delivery_report_key" {
  description = "Shared key the delivery-report webhook requires in its URL. Empty (the default) leaves the route answering 404 to everything, which is the correct state for any environment that has not deliberately turned it on."
  type        = string
  sensitive   = true
  default     = ""
}

variable "rbac_propagation_delay" {
  description = "How long to wait after granting a role before using it. An RBAC assignment is accepted by ARM before it is usable at the data plane, and Terraform has no primitive that waits for the difference - so this is a guess, and the only honest thing to do about a guess is to let a slower tenant raise it without editing a module."
  type        = string
  default     = "60s"
}
