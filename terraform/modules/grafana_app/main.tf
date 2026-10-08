locals {
  app_name     = "kanban-grafana-${var.env}"
  default_fqdn = "${local.app_name}.${var.container_app_env_default_domain}"
  hostnames    = compact([local.default_fqdn, var.custom_domain])
  root_url     = "https://${coalesce(var.custom_domain, local.default_fqdn)}"
  issuer       = "https://login.microsoftonline.com/${var.tenant_id}/v2.0"
  roles = {
    Viewer = "See the dashboards."
    Editor = "Also use Explore and edit dashboards that are not provisioned."
    Admin  = "Also manage data sources, users and settings."
  }
  ghcr_credentials_configured = nonsensitive(var.ghcr_token != "")
}

resource "azurerm_user_assigned_identity" "grafana" {
  tags                = var.tags
  name                = "kanban-grafana-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "random_uuid" "role" {
  for_each = local.roles
}

resource "azuread_application" "grafana" {
  display_name     = "kanban-grafana-${var.env}"
  sign_in_audience = "AzureADMyOrg"

  web {
    homepage_url  = local.root_url
    redirect_uris = flatten([for host in local.hostnames : ["https://${host}/login/azuread", "https://${host}/.auth/login/aad/callback"]])

    # The ingress sign-in has no client secret, so it uses the implicit flow for its ID token.
    implicit_grant {
      id_token_issuance_enabled = true
    }
  }

  dynamic "app_role" {
    for_each = local.roles
    content {
      allowed_member_types = ["User"]
      display_name         = app_role.key
      description          = app_role.value
      enabled              = true
      id                   = random_uuid.role[app_role.key].result
      value                = app_role.key
    }
  }
}

resource "azuread_service_principal" "grafana" {
  client_id                    = azuread_application.grafana.client_id
  app_role_assignment_required = true
}

resource "azuread_application_federated_identity_credential" "grafana" {
  application_id = azuread_application.grafana.id
  display_name   = "grafana-managed-identity"
  description    = "Grafana signs users in as its managed identity, so there is no client secret."
  audiences      = ["api://AzureADTokenExchange"]
  issuer         = local.issuer
  subject        = azurerm_user_assigned_identity.grafana.principal_id
}

resource "azurerm_role_assignment" "secrets" {
  for_each             = toset(compact([var.read_password_secret_name, var.ca_certificate_secret_name, local.ghcr_credentials_configured ? "GHCR-TOKEN" : ""]))
  scope                = "${var.key_vault_id}/secrets/${each.value}"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.grafana.principal_id
}

resource "azurerm_role_assignment" "monitoring_reader" {
  scope                = var.resource_group_id
  role_definition_name = "Monitoring Reader"
  principal_id         = azurerm_user_assigned_identity.grafana.principal_id
}

resource "azurerm_role_assignment" "log_analytics_reader" {
  scope                = var.log_analytics_workspace_id
  role_definition_name = "Log Analytics Reader"
  principal_id         = azurerm_user_assigned_identity.grafana.principal_id
}

resource "time_sleep" "wait_for_roles" {
  triggers = {
    secrets = join(",", [for assignment in azurerm_role_assignment.secrets : assignment.id])
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_container_app" "grafana" {
  tags                         = var.tags
  name                         = local.app_name
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"
  revision_mode                = "Single"

  depends_on = [time_sleep.wait_for_roles]

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.grafana.id]
  }

  secret {
    name                = "prometheus-read-password"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), var.read_password_secret_name)
    identity            = azurerm_user_assigned_identity.grafana.id
  }

  secret {
    name                = "prometheus-ca-cert"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), var.ca_certificate_secret_name)
    identity            = azurerm_user_assigned_identity.grafana.id
  }

  dynamic "secret" {
    for_each = local.ghcr_credentials_configured ? [1] : []
    content {
      name                = "ghcr-token"
      key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "GHCR-TOKEN")
      identity            = azurerm_user_assigned_identity.grafana.id
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

  ingress {
    external_enabled = true
    target_port      = 3000
    transport        = "auto"

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = 0
    max_replicas = 1

    container {
      name   = "grafana"
      image  = var.image
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "GF_SERVER_ROOT_URL"
        value = local.root_url
      }
      env {
        name  = "PROMETHEUS_URL"
        value = var.prometheus_url
      }
      env {
        name        = "PROMETHEUS_READ_PASSWORD"
        secret_name = "prometheus-read-password"
      }
      env {
        name        = "PROMETHEUS_CA_CERT"
        secret_name = "prometheus-ca-cert"
      }
      env {
        name  = "AZURE_SUBSCRIPTION_ID"
        value = var.subscription_id
      }
      env {
        name  = "GF_AZURE_MANAGED_IDENTITY_ENABLED"
        value = "true"
      }
      env {
        name  = "GF_AZURE_MANAGED_IDENTITY_CLIENT_ID"
        value = azurerm_user_assigned_identity.grafana.client_id
      }
      env {
        name  = "GF_AUTH_AZUREAD_ENABLED"
        value = "true"
      }
      env {
        name  = "GF_AUTH_AZUREAD_NAME"
        value = "Microsoft Entra ID"
      }
      env {
        name  = "GF_AUTH_AZUREAD_CLIENT_ID"
        value = azuread_application.grafana.client_id
      }
      env {
        name  = "GF_AUTH_AZUREAD_CLIENT_AUTHENTICATION"
        value = "managed_identity"
      }
      env {
        name  = "GF_AUTH_AZUREAD_MANAGED_IDENTITY_CLIENT_ID"
        value = azurerm_user_assigned_identity.grafana.client_id
      }
      env {
        name  = "GF_AUTH_AZUREAD_FEDERATED_CREDENTIAL_AUDIENCE"
        value = "api://AzureADTokenExchange"
      }
      env {
        name  = "GF_AUTH_AZUREAD_AUTH_URL"
        value = "https://login.microsoftonline.com/${var.tenant_id}/oauth2/v2.0/authorize"
      }
      env {
        name  = "GF_AUTH_AZUREAD_TOKEN_URL"
        value = "https://login.microsoftonline.com/${var.tenant_id}/oauth2/v2.0/token"
      }
      env {
        name  = "GF_AUTH_AZUREAD_SCOPES"
        value = "openid email profile"
      }
      env {
        name  = "GF_AUTH_AZUREAD_ALLOWED_ORGANIZATIONS"
        value = var.tenant_id
      }
      env {
        name  = "GF_AUTH_AZUREAD_ALLOW_SIGN_UP"
        value = "true"
      }
      env {
        name  = "GF_AUTH_AZUREAD_ROLE_ATTRIBUTE_STRICT"
        value = "true"
      }
      env {
        name  = "GF_AUTH_AZUREAD_USE_PKCE"
        value = "true"
      }

      startup_probe {
        transport               = "HTTP"
        port                    = 3000
        path                    = "/api/health"
        interval_seconds        = 5
        timeout                 = 3
        failure_count_threshold = 24
      }

      liveness_probe {
        transport = "HTTP"
        port      = 3000
        path      = "/api/health"
      }
    }
  }
}

# Nothing reaches Grafana without an Entra sign-in for a user assigned one of its roles.
resource "azapi_resource" "ingress_sign_in" {
  type      = "Microsoft.App/containerApps/authConfigs@2024-03-01"
  name      = "current"
  parent_id = azurerm_container_app.grafana.id

  body = {
    properties = {
      platform = {
        enabled = true
      }
      globalValidation = {
        unauthenticatedClientAction = "RedirectToLoginPage"
        redirectToProvider          = "azureactivedirectory"
      }
      identityProviders = {
        azureActiveDirectory = {
          enabled = true
          registration = {
            clientId     = azuread_application.grafana.client_id
            openIdIssuer = local.issuer
          }
          validation = {
            allowedAudiences = [azuread_application.grafana.client_id]
          }
        }
      }
      login = {
        preserveUrlFragmentsForLogins = true
      }
    }
  }
}
