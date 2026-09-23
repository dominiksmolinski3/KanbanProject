locals {
  app_port = 8080

  ghcr_credentials_configured = var.ghcr_token != ""
  acs_mail_configured         = var.acs_email_connection_string != ""
  delivery_reports_configured = var.mail_delivery_report_key != ""
  captcha_secret_configured   = var.captcha_secret != ""

  app_name = "kanban-api-${var.env}"

  browser_origin = "https://${var.web_app_name}.${var.container_app_env_default_domain}"

  cors_allowed_origins = join(",", concat([local.browser_origin], var.extra_cors_origins))
}

resource "azurerm_container_app" "main" {
  tags                         = var.tags
  name                         = local.app_name
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"
  revision_mode                = "Single"
  depends_on = [
    time_sleep.wait_for_secrets_user,
    time_sleep.wait_for_blob_contributor,
    azurerm_key_vault_secret.jwt_secret,
    azurerm_key_vault_secret.acs_email_connection_string,
    azurerm_key_vault_secret.mail_delivery_report_key,
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
    name                = "redis-access-key"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "REDIS-ACCESS-KEY")
    identity            = azurerm_user_assigned_identity.main.id
  }
  dynamic "secret" {
    for_each = local.acs_mail_configured ? [1] : []
    content {
      name                = "acs-email-connection-string"
      key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "ACS-EMAIL-CONNECTION-STRING")
      identity            = azurerm_user_assigned_identity.main.id
    }
  }
  dynamic "secret" {
    for_each = local.delivery_reports_configured ? [1] : []
    content {
      name                = "app-mail-delivery-report-key"
      key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "APP-MAIL-DELIVERY-REPORT-KEY")
      identity            = azurerm_user_assigned_identity.main.id
    }
  }
  dynamic "secret" {
    for_each = local.captcha_secret_configured ? [1] : []
    content {
      name                = "captcha-secret"
      key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "CAPTCHA-SECRET")
      identity            = azurerm_user_assigned_identity.main.id
    }
  }

  dynamic "secret" {
    for_each = local.ghcr_credentials_configured ? [1] : []

    content {
      name                = "ghcr-token"
      key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "GHCR-TOKEN")
      identity            = azurerm_user_assigned_identity.main.id
    }
  }

  secret {
    name  = "app-insights-connection-string"
    value = var.app_insights_connection_string
  }

  secret {
    name                = "rabbitmq-password"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "RABBITMQ-PASSWORD")
    identity            = azurerm_user_assigned_identity.main.id
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
      name   = "kanban-api"
      image  = "ghcr.io/${var.github_repository_owner}/kanbanproject-app:${var.app_image_tag}"
      cpu    = 0.5
      memory = "1Gi"

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
      dynamic "env" {
        for_each = local.acs_mail_configured ? [1] : []
        content {
          name        = "ACS_EMAIL_CONNECTION_STRING"
          secret_name = "acs-email-connection-string"
        }
      }
      env {
        name  = "ACS_EMAIL_SENDER_ADDRESS"
        value = var.acs_email_sender_address
      }
      dynamic "env" {
        for_each = local.delivery_reports_configured ? [1] : []
        content {
          name        = "APP_MAIL_DELIVERY_REPORT_KEY"
          secret_name = "app-mail-delivery-report-key"
        }
      }
      env {
        name  = "CAPTCHA_ENABLED"
        value = tostring(var.captcha_enabled)
      }
      dynamic "env" {
        for_each = local.captcha_secret_configured ? [1] : []
        content {
          name        = "CAPTCHA_SECRET"
          secret_name = "captcha-secret"
        }
      }
      env {
        name  = "SECURITY_RATE_LIMIT_TRUSTED_PROXY_COUNT"
        value = tostring(var.ingress_trusted_proxy_count)
      }
      env {
        name  = "SECURITY_RATE_LIMIT_REDIS_HOST"
        value = var.redis_hostname
      }
      env {
        name  = "SECURITY_RATE_LIMIT_REDIS_PORT"
        value = tostring(var.redis_port)
      }
      env {
        name  = "SECURITY_RATE_LIMIT_REDIS_SSL"
        value = "true"
      }
      env {
        name        = "SECURITY_RATE_LIMIT_REDIS_PASSWORD"
        secret_name = "redis-access-key"
      }
      env {
        name  = "SECURITY_CORS_ALLOWED_ORIGINS"
        value = local.cors_allowed_origins
      }
      env {
        name  = "AZURE_STORAGE_BLOB_ENDPOINT"
        value = var.storage_blob_endpoint
      }
      env {
        name  = "AZURE_STORAGE_IDENTITY_CLIENT_ID"
        value = azurerm_user_assigned_identity.main.client_id
      }
      env {
        name  = "ATTACHMENT_REPLICA_COUNT_HINT"
        value = tostring(var.max_replicas)
      }
      env {
        name  = "STOMP_RELAY_HOST"
        value = var.broker_app_name
      }
      env {
        name  = "STOMP_RELAY_PORT"
        value = tostring(var.broker_port)
      }
      env {
        name  = "STOMP_RELAY_USERNAME"
        value = var.broker_username
      }
      env {
        name        = "STOMP_RELAY_PASSWORD"
        secret_name = "rabbitmq-password"
      }
      env {
        name  = "LOG_FORMAT"
        value = "ecs"
      }

      env {
        name  = "DB_MAX_POOL_SIZE"
        value = tostring(max(1, floor(var.db_connection_budget / var.max_replicas)))
      }

      env {
        name        = "APPLICATIONINSIGHTS_CONNECTION_STRING"
        secret_name = "app-insights-connection-string"
      }

      env {
        name  = "APPLICATIONINSIGHTS_AUTHENTICATION_STRING"
        value = "Authorization=AAD;ClientId=${azurerm_user_assigned_identity.main.client_id}"
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
    external_enabled = false
    target_port      = local.app_port
    transport        = "http"

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
  triggers = {
    role_assignment_id = azurerm_role_assignment.key_vault_secrets_user.id
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_role_assignment" "monitoring_metrics_publisher" {
  scope                = var.app_insights_id
  role_definition_name = "Monitoring Metrics Publisher"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

resource "azurerm_role_assignment" "storage_blob_contributor" {
  scope                = var.storage_account_id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

resource "time_sleep" "wait_for_blob_contributor" {
  triggers = {
    role_assignment_id = azurerm_role_assignment.storage_blob_contributor.id
  }
  create_duration = var.rbac_propagation_delay
}

resource "random_password" "jwt_secret_key" {
  length  = 64
  special = false
}

resource "azurerm_key_vault_secret" "jwt_secret" {
  tags         = var.tags
  name         = "JWT-SECRET-KEY"
  value        = base64encode(random_password.jwt_secret_key.result)
  content_type = "base64 HMAC signing key"
  key_vault_id = var.key_vault_id

  lifecycle {
    # The JWT key is rotated out-of-band; without this an apply reverts it and signs everyone out.
    ignore_changes = [value]
  }
}

resource "azurerm_key_vault_secret" "acs_email_connection_string" {
  count = local.acs_mail_configured ? 1 : 0

  tags         = var.tags
  name         = "ACS-EMAIL-CONNECTION-STRING"
  value        = var.acs_email_connection_string
  content_type = "endpoint=...;accesskey=..."
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "mail_delivery_report_key" {
  count = local.delivery_reports_configured ? 1 : 0

  tags         = var.tags
  name         = "APP-MAIL-DELIVERY-REPORT-KEY"
  value        = var.mail_delivery_report_key
  content_type = "webhook shared key"
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "captcha_secret" {
  count = local.captcha_secret_configured ? 1 : 0

  tags         = var.tags
  name         = "CAPTCHA-SECRET"
  value        = var.captcha_secret
  content_type = "reCAPTCHA shared secret"
  key_vault_id = var.key_vault_id
}

resource "azurerm_key_vault_secret" "ghcr_token" {
  tags  = var.tags
  count = local.ghcr_credentials_configured ? 1 : 0

  name         = "GHCR-TOKEN"
  value        = var.ghcr_token
  content_type = "GitHub PAT, read:packages"
  key_vault_id = var.key_vault_id
}
