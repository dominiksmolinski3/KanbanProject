locals {
  app_port = 8080

  ghcr_credentials_configured = var.ghcr_token != ""
}

resource "azurerm_container_app" "main" {
  tags                         = var.tags
  name                         = "kanban-app-${var.env}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_env_id
  revision_mode                = "Single"
  depends_on = [
    time_sleep.wait_for_secrets_user,
    azurerm_key_vault_secret.jwt_secret,
    azurerm_key_vault_secret.acs_email_connection_string,
    azurerm_key_vault_secret.captcha_secret,
    azurerm_key_vault_secret.ghcr_token,
  ]

  secret {
    name                = "postgres-connection-string"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "POSTGRES-CONNECTION-STRING")
    identity            = azurerm_user_assigned_identity.main.id
  }
  secret {
    name                = "postgres-user"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "POSTGRES-USER")
    identity            = azurerm_user_assigned_identity.main.id
  }
  secret {
    name                = "postgres-password"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "POSTGRES-PASSWORD")
    identity            = azurerm_user_assigned_identity.main.id
  }
  secret {
    name                = "jwt-secret-key"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "JWT-SECRET-KEY")
    identity            = azurerm_user_assigned_identity.main.id
  }
  secret {
    name                = "acs-email-connection-string"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "ACS-EMAIL-CONNECTION-STRING")
    identity            = azurerm_user_assigned_identity.main.id
  }
  secret {
    name                = "captcha-secret"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "CAPTCHA-SECRET")
    identity            = azurerm_user_assigned_identity.main.id
  }

  dynamic "secret" {
    for_each = local.ghcr_credentials_configured ? [1] : []

    content {
      name                = "ghcr-token"
      key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "GHCR-TOKEN")
      identity            = azurerm_user_assigned_identity.main.id
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
      name   = "kanban-app"
      image  = "ghcr.io/${var.github_repository_owner}/kanbanproject-app:${var.app_image_tag}"
      cpu    = 0.25
      memory = "0.5Gi"

      env {
        name        = "SPRING_DATASOURCE_URL"
        secret_name = "postgres-connection-string"
      }
      env {
        name        = "SPRING_DATASOURCE_USERNAME"
        secret_name = "postgres-user"
      }
      env {
        name        = "SPRING_DATASOURCE_PASSWORD"
        secret_name = "postgres-password"
      }
      env {
        name        = "JWT_SECRET_KEY"
        secret_name = "jwt-secret-key"
      }
      env {
        name        = "ACS_EMAIL_CONNECTION_STRING"
        secret_name = "acs-email-connection-string"
      }
      env {
        name  = "ACS_EMAIL_SENDER_ADDRESS"
        value = var.acs_email_sender_address
      }
      env {
        name  = "CAPTCHA_ENABLED"
        value = tostring(var.captcha_enabled)
      }
      env {
        name        = "CAPTCHA_SECRET"
        secret_name = "captcha-secret"
      }
      env {
        name  = "SECURITY_RATE_LIMIT_TRUSTED_PROXY_COUNT"
        value = tostring(var.ingress_trusted_proxy_count)
      }


      startup_probe {
        transport               = "HTTP"
        port                    = local.app_port
        path                    = "/actuator/health/readiness"
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 30
      }

      readiness_probe {
        transport               = "HTTP"
        port                    = local.app_port
        path                    = "/actuator/health/readiness"
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 3
        success_count_threshold = 1
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = local.app_port
        path                    = "/actuator/health/liveness"
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }
    }

    min_replicas = 1
    max_replicas = var.max_replicas

    http_scale_rule {
      name                = "http-scale"
      concurrent_requests = "50"
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
    identity_ids = [azurerm_user_assigned_identity.main.id]
  }
}

resource "azurerm_user_assigned_identity" "main" {
  tags                = var.tags
  name                = "kanban-app-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "azurerm_role_assignment" "key_vault_secrets_user" {
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

resource "time_sleep" "wait_for_secrets_user" {
  depends_on      = [azurerm_role_assignment.key_vault_secrets_user]
  create_duration = "60s"
}

# outside the app needs to know it, so no one should have to type it. Same pattern the
# postgres module uses for its admin password.
#
# Stored base64-encoded because JwtService.getSignInKey runs Decoders.BASE64.decode()
# and then Keys.hmacShaKeyFor(), which throws on anything under 32 decoded bytes.
# 64 random characters clear that comfortably.
resource "random_password" "jwt_secret_key" {
  length  = 64
  special = false
}

resource "azurerm_key_vault_secret" "jwt_secret" {
  tags         = var.tags
  name         = "JWT-SECRET-KEY"
  value        = base64encode(random_password.jwt_secret_key.result)
  key_vault_id = var.key_vault_id

  # Rotating the JWT key signs out every user, so it is done deliberately and
  # out-of-band (`az keyvault secret set`). Without this, the next apply would
  # silently revert it and sign everyone out a second time.
  lifecycle {
    ignore_changes = [value]
  }
}

# The connection string is issued by Azure Communication Services, so it cannot be generated - it
# comes in as a variable and sits in tfvars and state in plaintext, the same deliberate acceptance
# as captcha_secret (see terraform/README.md, Secrets). Empty is allowed: the app reads a blank
# ACS_EMAIL_CONNECTION_STRING as "mail off" rather than failing to boot.
resource "azurerm_key_vault_secret" "acs_email_connection_string" {
  tags         = var.tags
  name         = "ACS-EMAIL-CONNECTION-STRING"
  value        = var.acs_email_connection_string
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "captcha_secret" {
  tags         = var.tags
  name         = "CAPTCHA-SECRET"
  value        = var.captcha_secret
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "ghcr_token" {
  tags  = var.tags
  count = local.ghcr_credentials_configured ? 1 : 0

  name         = "GHCR-TOKEN"
  value        = var.ghcr_token
  key_vault_id = var.key_vault_id
}
