locals {
  app_port = 8080

  # The public edge. This is the app that holds the FQDN a browser types, which is why the ingress
  # restrictions and the CORS origin below are computed from its name rather than the API app's.
  app_name = "kanban-web-${var.env}"

  # Where nginx sends /api, /ws and /v3/api-docs.
  #
  # https, not http, and that is measured rather than assumed. A Container App with
  # external_enabled = false still terminates TLS on its internal ingress and answers plain HTTP
  # with a 301 to the https form of the same URL - verified against kanban-app-dev on 15 Sep 2026,
  # which is a redirect nginx would hand straight back to the browser, pointing it at a hostname
  # only the inside of the environment can resolve. The two ways out are
  # `allow_insecure_connections = true` on the API app, or speaking TLS to it; this takes the
  # second, because the certificate the internal ingress presents carries
  # `*.internal.<environment-domain>` as a SAN, is issued by "Microsoft TLS G2 RSA CA" and verifies
  # against the stock CA bundle with return code 0 - so nginx can authenticate the hop rather than
  # merely encrypting it, and nothing here has to declare a connection insecure to make it work.
  #
  # The FQDN is composed rather than read from the API app's own `ingress[0].fqdn`, so the two
  # modules stay independent of each other's resources; the pattern is fixed once the environment
  # exists. It has to agree with api_app's local.app_name, which is what api_upstream_matches_api_app
  # in the root module asserts.
  api_upstream = "https://kanban-api-${var.env}.internal.${var.container_app_env_default_domain}"

  ghcr_credentials_configured = var.ghcr_token != ""
}

resource "azurerm_container_app" "main" {
  tags                         = var.tags
  name                         = local.app_name
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"
  revision_mode                = "Single"

  depends_on = [time_sleep.wait_for_secrets_user]

  dynamic "secret" {
    for_each = local.ghcr_credentials_configured ? [1] : []

    content {
      name                = "ghcr-token"
      key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "GHCR-TOKEN")
      identity            = azurerm_user_assigned_identity.web.id
    }
  }

  dynamic "registry" {
    for_each = local.ghcr_credentials_configured ? [1] : []

    content {
      server               = "ghcr.io"
      username             = var.ghcr_username
      password_secret_name = "ghcr-token"
    }
  }

  template {
    container {
      name   = "kanban-web"
      image  = "ghcr.io/${var.github_repository_owner}/kanbanproject-web:${var.app_image_tag}"
      cpu    = 0.25
      memory = "0.5Gi"

      # The one thing this container is configured with. nginx renders it into its server block at
      # start through the stock entrypoint's envsubst, so the same image runs here and against
      # http://app:8080 in docker-compose.
      env {
        name  = "API_UPSTREAM"
        value = local.api_upstream
      }

      # nginx's own probe: a one-line `return 200` with no upstream behind it. The JVM's readiness
      # and liveness groups stay on the API app and are probed there directly - an edge that
      # reported itself unhealthy because the API was restarting would take the shell down with it,
      # and the shell is what tells a person the API is restarting.
      startup_probe {
        transport               = "HTTP"
        port                    = local.app_port
        path                    = "/healthz"
        interval_seconds        = 3
        timeout                 = 3
        failure_count_threshold = 10
      }

      readiness_probe {
        transport               = "HTTP"
        port                    = local.app_port
        path                    = "/healthz"
        interval_seconds        = 10
        timeout                 = 3
        failure_count_threshold = 3
        success_count_threshold = 1
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = local.app_port
        path                    = "/healthz"
        interval_seconds        = 30
        timeout                 = 3
        failure_count_threshold = 3
      }
    }

    min_replicas = 1
    max_replicas = var.max_replicas

    # Tuned for page loads rather than API calls: a browser opening the board fetches the shell, the
    # hashed bundle and a locale file, all of which nginx answers from disk in a few milliseconds.
    http_scale_rule {
      name                = "http-scale"
      concurrent_requests = "100"
    }
  }

  ingress {
    external_enabled = true
    target_port      = local.app_port
    transport        = "http"

    # These guard the public edge, which is what this app now is and the API app no longer is.
    dynamic "ip_security_restriction" {
      for_each = toset(var.allowed_ingress_cidrs)
      content {
        name             = "allow-${replace(ip_security_restriction.value, "/[^a-zA-Z0-9]/", "-")}"
        description      = "Allow ${ip_security_restriction.value}"
        ip_address_range = ip_security_restriction.value
        action           = "Allow"
      }
    }

    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.web.id]
  }
}

resource "azurerm_user_assigned_identity" "web" {
  tags                = var.tags
  name                = "kanban-web-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

# Scoped to the one secret rather than to the vault, which is the whole reason this is a second
# identity instead of a reuse of the API app's.
#
# The edge needs exactly one thing from Key Vault: the token that pulls its own image. A
# vault-scoped grant would let the nginx container read the Postgres password and the JWT signing
# key - a strictly worse posture than the monolith had, arrived at by splitting a deployment for
# reasons that had nothing to do with secrets.
resource "azurerm_role_assignment" "ghcr_token_reader" {
  count = local.ghcr_credentials_configured ? 1 : 0

  scope                = "${var.key_vault_id}/secrets/GHCR-TOKEN"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.web.principal_id
}

# Keyed on the assignment's id rather than ordered by depends_on, so the wait is recreated whenever
# the grant is - see the same pattern in modules/api_app and modules/key_vault. `depends_on` on a
# counted resource means "all instances", so this still orders correctly when the count is zero.
resource "time_sleep" "wait_for_secrets_user" {
  triggers = {
    role_assignment_id = join(",", azurerm_role_assignment.ghcr_token_reader[*].id)
  }
  create_duration = var.rbac_propagation_delay
}
