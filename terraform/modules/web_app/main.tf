locals {
  app_port = 8080

  app_name = "kanban-web-${var.env}"

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

      env {
        name  = "API_UPSTREAM"
        value = local.api_upstream
      }

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

    http_scale_rule {
      name                = "http-scale"
      concurrent_requests = "100"
    }
  }

  ingress {
    external_enabled = true
    target_port      = local.app_port
    transport        = "http"

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

resource "azurerm_role_assignment" "ghcr_token_reader" {
  count = local.ghcr_credentials_configured ? 1 : 0

  scope                = "${var.key_vault_id}/secrets/GHCR-TOKEN"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.web.principal_id
}

resource "time_sleep" "wait_for_secrets_user" {
  triggers = {
    role_assignment_id = join(",", azurerm_role_assignment.ghcr_token_reader[*].id)
  }
  create_duration = var.rbac_propagation_delay
}
