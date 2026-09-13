locals {
  app_port = 8080

  # An optional secret is one whose variable defaults to "", and every one of them has to be
  # switched off in three places together: the Key Vault secret is not created, the container app
  # declares no `secret` for it, and no `env` references that secret name.
  #
  # Not a style preference. Key Vault stores an empty secret value happily, and Container Apps then
  # refuses to resolve it - "Unable to get value using Managed identity ... unable to fetch secret"
  # - so the revision never provisions and the apply fails outright. Measured on dev, 13 Sep 2026,
  # on the first apply after the delivery-report work: five resources applied and the container app
  # would not. `ghcr_token` had the pattern from the start; the other three did not, and the only
  # reason dev had not hit it before is that dev happens to supply ACS and captcha values.
  #
  # Which means "blank is the safe default" was true of the application and false of the
  # deployment: an environment that leaves any of these empty could not be applied at all. uat is
  # documented to run with mail off, so uat's first apply would have failed on ACS.
  ghcr_credentials_configured = var.ghcr_token != ""
  acs_mail_configured         = var.acs_email_connection_string != ""
  delivery_reports_configured = var.mail_delivery_report_key != ""
  captcha_secret_configured   = var.captcha_secret != ""

  # The Container Apps FQDN pattern (<app-name>.<environment-default-domain>) is fixed once the
  # environment exists, so this is knowable before azurerm_container_app.main is created -
  # referencing its own ingress fqdn here would be a dependency cycle.
  app_name    = "kanban-app-${var.env}"
  self_origin = "https://${local.app_name}.${var.container_app_env_default_domain}"

  # Browsers send an Origin header - and Spring's CORS filter runs - even for a same-origin
  # request, whenever a <script>/<link> carries crossorigin (Vite sets it on every module script
  # and its preloaded stylesheets). SecurityConfiguration's default allow-list only ever knew
  # about the production domain and localhost, so it 403'd the app's own generated URL outright.
  # Setting this always, rather than only when extra_cors_origins is non-empty, is what keeps
  # every environment's own origin covered without anyone having to remember to.
  cors_allowed_origins = join(",", concat([local.self_origin], var.extra_cors_origins))
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
      # Task attachments. Neither of these is a secret and neither goes through Key Vault: the
      # endpoint is a public address and the client id names an identity rather than proving
      # anything. What authorises the app is the role assignment below, held by the identity the
      # container already runs as - there is no storage key anywhere in this deployment.
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

# Read and write the attachment blobs, and - because the application creates its own container on
# first start - make the container to put them in. Contributor rather than the narrower Storage Blob
# Data Reader/Writer pair for exactly that reason: creating a container is a container-level
# operation that Writer does not carry.
#
# It is also the only way the application reaches the blobs at all: shared_access_key_enabled is
# false, so there is no account key and no connection string to build one from. The earlier version
# of this comment described a user delegation SAS handed to the browser; that design was reversed
# when the account was closed to the internet, and the bytes are proxied through the app now.
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

# The key in the delivery-report webhook's URL. Empty is not only allowed but is the default for
# every environment: with no key the application's webhook answers 404 to everything and the Event
# Grid subscription in the diagnostics module is not created either, so the one unauthenticated
# write in this deployment does not exist unless somebody has deliberately switched it on.
#
# A secret rather than a plain env value because it is a credential - anyone holding it can post
# delivery reports. What that buys is small (a wrong delivery status on a row that really was sent)
# and it is still not a string to leave sitting in a container template.
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
