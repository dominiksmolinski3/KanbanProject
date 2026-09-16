locals {
  app_port = 8080

  # An optional secret (a variable defaulting to "") must be switched off in three places together:
  # no Key Vault secret created, no container-app `secret` block, no `env` referencing it. Key Vault
  # accepts an empty secret value, but Container Apps then fails to resolve it and the revision never
  # provisions - so "blank is safe" is true of the app but false of the deployment.
  ghcr_credentials_configured = var.ghcr_token != ""
  acs_mail_configured         = var.acs_email_connection_string != ""
  delivery_reports_configured = var.mail_delivery_report_key != ""
  captcha_secret_configured   = var.captcha_secret != ""

  # Internal ingress only, reachable at <app-name>.internal.<environment-default-domain> - a fixed
  # pattern so the web module can compose the same string without depending on this module's
  # resources.
  app_name = "kanban-api-${var.env}"

  # Vite marks its module scripts/stylesheets `crossorigin`, so the browser sends an Origin header
  # even on a same-origin request - and nginx forwards it unchanged, so what Spring's CORS filter
  # sees is the *web* app's FQDN, not this one's. Getting it wrong is a silent 403 on every API call,
  # not a 500.
  browser_origin = "https://${var.web_app_name}.${var.container_app_env_default_domain}"

  # Setting this always, rather than only when extra_cors_origins is non-empty, is what keeps
  # every environment's own origin covered without anyone having to remember to.
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
    # `depends_on` on a counted resource is legal and means "all instances", so these still order
    # correctly when the count is zero - there is simply nothing to wait for.
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
      name  = "kanban-api"
      image = "ghcr.io/${var.github_repository_owner}/kanbanproject-app:${var.app_image_tag}"
      # Doubled with the container split - the cheapest item on the front-door scaling document's
      # list. MaxRAMPercentage=60 in the Dockerfile keeps the heap following this limit automatically.
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
      # Absent rather than empty, which the application already reads the same way: a blank
      # ACS_EMAIL_CONNECTION_STRING and an unset one both select DisabledEmailSender.
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
      # Unset leaves app.mail.delivery-report-key blank, which is what makes the webhook answer
      # 404 to everything - the state every fresh clone and CI run is already in.
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
      # CAPTCHA_ENABLED above is what decides whether the check runs; with it off the secret is
      # not read, and CaptchaVerifier refuses to start enabled with no secret either way.
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
        name  = "SECURITY_CORS_ALLOWED_ORIGINS"
        value = local.cors_allowed_origins
      }
      # Task attachments. Neither value is a secret, so neither goes through Key Vault - what
      # authorises the app is the role assignment below, held by the identity it already runs as.
      # There is no storage key anywhere in this deployment.
      env {
        name  = "AZURE_STORAGE_BLOB_ENDPOINT"
        value = var.storage_blob_endpoint
      }
      env {
        name  = "AZURE_STORAGE_IDENTITY_CLIENT_ID"
        value = azurerm_user_assigned_identity.main.client_id
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


  # Internal: nothing outside the Managed Environment can reach Spring, which is why the
  # ip_security_restriction blocks moved to the web module - there is no public ingress left here to
  # restrict. `transport = "http"` describes the container's own port, not the wire: the internal
  # ingress terminates TLS regardless and answers plain HTTP with a 301, which is why the edge speaks
  # https to it rather than carrying allow_insecure_connections.
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
  tags = var.tags
  # Deliberately not renamed with the app. Renaming a user-assigned identity destroys and recreates
  # it, which means a new principal id, new role assignments against Key Vault and the storage
  # account, and another wait on RBAC propagation - all to change a string nothing reads. The edge
  # gets its own identity under its own name; this one keeps the name its grants were made to.
  name                = "kanban-app-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "azurerm_role_assignment" "key_vault_secrets_user" {
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

# Keyed on the assignment's id rather than ordered by `depends_on`, so the wait is recreated
# whenever the grant is. See the note on the same pattern in modules/key_vault: `depends_on` waited
# on the first apply and silently stopped waiting on every one after it, which is the opposite of
# what this is for - a replaced assignment is a brand-new grant with no propagation behind it.
resource "time_sleep" "wait_for_secrets_user" {
  triggers = {
    role_assignment_id = azurerm_role_assignment.key_vault_secrets_user.id
  }
  create_duration = var.rbac_propagation_delay
}

# Read/write the attachment blobs, and create the container the app puts them in on first start.
# Contributor rather than the narrower Storage Blob Data Reader/Writer pair because creating a
# container is a container-level operation Writer doesn't grant - and it's the only path in, since
# shared_access_key_enabled is false and there is no account key to build a connection string from.
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

# Generated randomly so nothing outside the app needs to know it - the same pattern the postgres
# module uses for its admin password. Stored base64-encoded because JwtService.getSignInKey calls
# Decoders.BASE64.decode() then Keys.hmacShaKeyFor(), which throws under 32 decoded bytes; 64 random
# characters clears that.
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
  count = local.acs_mail_configured ? 1 : 0

  tags         = var.tags
  name         = "ACS-EMAIL-CONNECTION-STRING"
  value        = var.acs_email_connection_string
  content_type = "endpoint=...;accesskey=..."
  key_vault_id = var.key_vault_id
}

# The key in the delivery-report webhook's URL. Empty by default everywhere: with no key the webhook
# answers 404 to everything and no Event Grid subscription is created either, so this write path
# doesn't exist unless deliberately switched on. Stored as a secret, not a plain env value, because
# it's a credential worth keeping out of the container template.
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
