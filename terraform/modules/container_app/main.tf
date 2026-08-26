resource "azurerm_container_app" "main" {
  name                         = "kanban-app-${var.env}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_env_id
  revision_mode                = "Single"
  depends_on = [
    time_sleep.wait_for_secrets_user,
    azurerm_key_vault_secret.jwt_secret,
    azurerm_key_vault_secret.spring_mail_username,
    azurerm_key_vault_secret.spring_mail_password,
    azurerm_key_vault_secret.captcha_secret,
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
    name                = "spring-mail-username"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "SPRING-MAIL-USERNAME")
    identity            = azurerm_user_assigned_identity.main.id
  }
  secret {
    name                = "spring-mail-password"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "SPRING-MAIL-PASSWORD")
    identity            = azurerm_user_assigned_identity.main.id
  }
  secret {
    name                = "captcha-secret"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "CAPTCHA-SECRET")
    identity            = azurerm_user_assigned_identity.main.id
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
        name        = "SPRING_MAIL_USERNAME"
        secret_name = "spring-mail-username"
      }
      env {
        name        = "SPRING_MAIL_PASSWORD"
        secret_name = "spring-mail-password"
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
        name  = "VITE_RECAPTCHA_SITE_KEY"
        value = var.vite_recaptcha_site_key
      }
    }

    min_replicas = 1
    max_replicas = 5

    http_scale_rule {
      name                = "http-scale"
      concurrent_requests = "50"
    }

  }


  ingress {
    external_enabled = true
    target_port      = 8080
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

resource "azurerm_key_vault_secret" "spring_mail_username" {
  name         = "SPRING-MAIL-USERNAME"
  value        = var.spring_mail_username
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "spring_mail_password" {
  name         = "SPRING-MAIL-PASSWORD"
  value        = var.spring_mail_password
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "captcha_secret" {
  name         = "CAPTCHA-SECRET"
  value        = var.captcha_secret
  key_vault_id = var.key_vault_id
}
