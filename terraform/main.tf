locals {
  tags = merge(
    {
      environment = var.env
      application = "kanban"
      managed_by  = "terraform"
      owner       = var.owner_tag
    },
    var.extra_tags,
  )

  log_retention_days = {
    dev  = 30
    uat  = 30
    prod = 90
  }
}

resource "azurerm_resource_group" "main" {
  tags     = local.tags
  name     = var.resource_group_name
  location = var.location

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = can(regex("(^|[^a-z])${var.env}([^a-z]|$)", var.resource_group_name))
      error_message = "resource_group_name (${var.resource_group_name}) does not name env (${var.env}); the -var-file and the backend key are probably from different environments."
    }

    /*
     * MAIL-02, turned from a step somebody has to remember into a plan that refuses.
     *
     * With no connection string the app starts normally and drops every message, which is the
     * right default for CI and a fresh clone and is the wrong one here. In production it means
     * signup answers 200, the account is written unverified, the code is generated and stored, and
     * the mail never leaves: a user who cannot log in, and an operator looking at a healthy
     * revision with no error in it. Both variables are required because one without the other is
     * also mail off - EmailConfiguration wants the pair and warns about exactly that.
     *
     * Deliberately on prod alone. dev and uat are expected to run with mail disabled, which is how
     * the Cypress stack and every local boot work; and deliberately with no escape hatch, because
     * a flag saying "yes, production without mail" describes a deployment whose users cannot
     * complete signup. The cost is that the Communication Services resource has to exist before
     * prod is first applied, and that ordering is the finding rather than a side effect of it.
     *
     * This is a plan-time refusal for the same reason the two Postgres preconditions are: the
     * alternative is a container that deploys, goes healthy, and silently sends nobody anything.
     */
    precondition {
      condition = var.env != "prod" || (
        var.acs_email_connection_string != "" && var.acs_email_sender_address != ""
      )
      error_message = <<-EOT
        prod would deploy with mail switched off (MAIL-02).

        Set both for env = "prod":
          acs_email_connection_string
          acs_email_sender_address

        Empty means mail off: the app starts, signup answers 200, the
        verification code is stored, and the message is dropped. The
        revision is healthy and there is nothing in the log to look at.

        Create the Communication Services resource, link a domain (an
        Azure-managed *.azurecomm.net subdomain needs no DNS), and put
        both values in the gitignored prod.local.tfvars.

        See terraform/README.md, section "Mail".
      EOT
    }
  }
}

resource "azurerm_log_analytics_workspace" "main" {
  tags                = local.tags
  name                = "law-kanban-${var.env}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "PerGB2018"
  retention_in_days   = lookup(local.log_retention_days, var.env, 30)
}

module "vnet" {
  source                          = "./modules/vnet"
  resource_group_name             = azurerm_resource_group.main.name
  location                        = azurerm_resource_group.main.location
  env                             = var.env
  log_analytics_workspace_id      = azurerm_log_analytics_workspace.main.id
  ingress_source_address_prefixes = var.ingress_source_address_prefixes
  tags                            = local.tags
}

module "key_vault" {
  source                      = "./modules/key_vault"
  resource_group_name         = azurerm_resource_group.main.name
  location                    = azurerm_resource_group.main.location
  env                         = var.env
  allowed_subnet_id           = module.vnet.backend_subnet_id
  private_endpoint_subnet_id  = module.vnet.private_endpoint_subnet_id
  vnet_id                     = module.vnet.id
  ip_rules                    = var.key_vault_allowed_ips
  allow_azure_services_bypass = var.key_vault_allow_azure_services_bypass
  network_default_action      = var.key_vault_network_default_action
  purge_protection_enabled    = var.key_vault_purge_protection_enabled
  soft_delete_retention_days  = var.key_vault_soft_delete_retention_days
  rbac_propagation_delay      = var.rbac_propagation_delay
  tags                        = local.tags
}

module "postgres" {
  source              = "./modules/postgres"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  env                 = var.env
  vnet_id             = module.vnet.id
  subnet_id           = module.vnet.db_subnet_id
  key_vault_id        = module.key_vault.id

  sku_name                     = var.postgres_sku_name
  storage_mb                   = var.postgres_storage_mb
  zone                         = var.postgres_zone
  high_availability_mode       = var.postgres_high_availability_mode
  standby_availability_zone    = var.postgres_standby_availability_zone
  backup_retention_days        = var.postgres_backup_retention_days
  geo_redundant_backup_enabled = var.postgres_geo_redundant_backup_enabled
  tags                         = local.tags

  depends_on = [module.key_vault]
}

module "storage" {
  source                     = "./modules/storage"
  resource_group_name        = azurerm_resource_group.main.name
  location                   = azurerm_resource_group.main.location
  env                        = var.env
  replication_type           = var.storage_replication_type
  vnet_id                    = module.vnet.id
  private_endpoint_subnet_id = module.vnet.storage_subnet_id
  tags                       = local.tags

  # The two stores hold halves of the same attachment and nothing joins them, so the windows in
  # which each can be rolled back have to be the same length or a restore produces rows with no
  # bytes. Tied by default; attachment_retention_days unties it deliberately.
  retention_days = coalesce(var.attachment_retention_days, var.postgres_backup_retention_days)
}

# The public edge: nginx, the bundle, and the proxy in front of the API. It holds the only external
# ingress in this deployment, which is why the ingress restrictions and the origin everything else
# is told about are its.
module "web_app" {
  source                           = "./modules/web_app"
  resource_group_name              = azurerm_resource_group.main.name
  location                         = azurerm_resource_group.main.location
  env                              = var.env
  container_app_env_id             = module.vnet.container_app_env_id
  container_app_env_default_domain = module.vnet.container_app_env_default_domain
  rbac_propagation_delay           = var.rbac_propagation_delay
  app_image_tag                    = var.app_image_tag
  max_replicas                     = var.web_max_replicas
  allowed_ingress_cidrs            = var.allowed_ingress_cidrs
  key_vault_uri                    = module.key_vault.uri
  key_vault_id                     = module.key_vault.id
  github_repository_owner          = var.github_repository_owner
  ghcr_username                    = var.ghcr_username
  ghcr_token                       = var.ghcr_token
  tags                             = local.tags

  depends_on = [module.key_vault]
}

module "api_app" {
  source                           = "./modules/api_app"
  resource_group_name              = azurerm_resource_group.main.name
  location                         = azurerm_resource_group.main.location
  env                              = var.env
  container_app_env_id             = module.vnet.container_app_env_id
  container_app_env_default_domain = module.vnet.container_app_env_default_domain
  extra_cors_origins               = var.extra_cors_origins
  rbac_propagation_delay           = var.rbac_propagation_delay
  app_image_tag                    = var.app_image_tag
  max_replicas                     = var.api_max_replicas
  key_vault_uri                    = module.key_vault.uri
  key_vault_id                     = module.key_vault.id
  github_repository_owner          = var.github_repository_owner
  ghcr_username                    = var.ghcr_username
  ghcr_token                       = var.ghcr_token
  acs_email_connection_string      = var.acs_email_connection_string
  acs_email_sender_address         = var.acs_email_sender_address
  captcha_enabled                  = var.captcha_enabled
  captcha_secret                   = var.captcha_secret
  storage_account_id               = module.storage.id
  storage_blob_endpoint            = module.storage.blob_endpoint
  tags                             = local.tags

  # The browser's origin is the edge's FQDN, not this app's. Read from the web module's output
  # rather than composed a second time here - see local.browser_origin in the module.
  web_app_name = module.web_app.app_name

  ingress_trusted_proxy_count = var.ingress_trusted_proxy_count

  depends_on = [module.key_vault, module.postgres, module.storage]

  mail_delivery_report_key = var.mail_delivery_report_key
}

# Two modules compose the same FQDN from the same pattern and neither reads the other's resources,
# which is what keeps them independent and is also how they could silently disagree: rename the API
# app and the edge goes on proxying to a name nothing answers, which is a 502 on every API call and
# a green apply. Checked here because this is the one place both outputs are in scope.
check "api_upstream_matches_api_app" {
  assert {
    condition     = strcontains(module.web_app.api_upstream, "//${module.api_app.app_name}.internal.")
    error_message = "The edge proxies to ${module.web_app.api_upstream}, which does not name the API app (${module.api_app.app_name}). One of the two name patterns has moved without the other."
  }
}

module "diagnostics" {
  source = "./modules/diagnostics"

  env                        = var.env
  location                   = azurerm_resource_group.main.location
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
  # The API app. Every alert in this module is about the JVM - restarts, 5xx, the dead-letter and
  # bounce queries - and none of them is about nginx. An edge that falls over takes the whole origin
  # with it and shows up in the same 5xx rule from the other side, so a second set of alerts here
  # would mostly double every page.
  container_app_id     = module.api_app.container_app_id
  container_app_env_id = module.vnet.container_app_env_id
  resource_group_name  = azurerm_resource_group.main.name
  alert_email          = var.alert_email
  tags                 = local.tags

  key_vault_id                 = module.key_vault.id
  postgres_server_id           = module.postgres.postgres_server_id
  acs_communication_service_id = var.acs_communication_service_id

  # The webhook's address is the *edge's* ingress plus the key that authenticates it, assembled in
  # the diagnostics module so the two cannot be configured into disagreeing with each other. It has
  # to be the edge: Event Grid calls this URL from outside Azure's view of this VNet and cannot
  # reach an internal ingress at all. nginx proxies /api/mail/delivery-reports like any other /api
  # path, for free, and the ordering constraint the README records - the endpoint must be serving
  # before this resource can be created, because Event Grid validates it by calling it - now
  # depends on the web app being up rather than the API app.
  container_app_url        = module.web_app.container_app_url
  mail_delivery_report_key = var.mail_delivery_report_key
}
