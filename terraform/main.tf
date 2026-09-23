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

resource "azurerm_application_insights" "main" {
  tags                         = local.tags
  name                         = "appi-kanban-${var.env}"
  location                     = azurerm_resource_group.main.location
  resource_group_name          = azurerm_resource_group.main.name
  workspace_id                 = azurerm_log_analytics_workspace.main.id
  application_type             = "java"
  local_authentication_enabled = false
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

  retention_days = coalesce(var.attachment_retention_days, var.postgres_backup_retention_days)
}

module "redis" {
  source                     = "./modules/redis"
  resource_group_name        = azurerm_resource_group.main.name
  location                   = azurerm_resource_group.main.location
  env                        = var.env
  vnet_id                    = module.vnet.id
  private_endpoint_subnet_id = module.vnet.private_endpoint_subnet_id
  key_vault_id               = module.key_vault.id
  tags                       = local.tags

  depends_on = [module.key_vault]
}

module "broker" {
  source                 = "./modules/broker"
  resource_group_name    = azurerm_resource_group.main.name
  location               = azurerm_resource_group.main.location
  env                    = var.env
  container_app_env_id   = module.vnet.container_app_env_id
  key_vault_uri          = module.key_vault.uri
  key_vault_id           = module.key_vault.id
  rbac_propagation_delay = var.rbac_propagation_delay
  tags                   = local.tags

  depends_on = [module.key_vault]
}

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
  db_connection_budget             = var.api_db_connection_budget
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
  redis_hostname                   = module.redis.hostname
  redis_port                       = module.redis.port
  broker_app_name                  = module.broker.app_name
  broker_port                      = module.broker.port
  broker_username                  = module.broker.username
  tags                             = local.tags

  web_app_name = module.web_app.app_name

  ingress_trusted_proxy_count = var.ingress_trusted_proxy_count

  depends_on = [module.key_vault, module.postgres, module.storage, module.redis, module.broker]

  mail_delivery_report_key = var.mail_delivery_report_key

  app_insights_id                = azurerm_application_insights.main.id
  app_insights_connection_string = azurerm_application_insights.main.connection_string
}

check "api_connection_budget_fits_the_server" {
  assert {
    condition     = var.api_db_connection_budget <= module.postgres.usable_connections
    error_message = "The API fleet may hold ${var.api_db_connection_budget} connections and ${var.postgres_sku_name} leaves ${module.postgres.usable_connections} for ordinary logins once the superuser reserve is taken. Lower api_db_connection_budget or move to a larger SKU."
  }
}

check "every_api_replica_gets_a_connection" {
  assert {
    condition     = floor(var.api_db_connection_budget / var.api_max_replicas) >= 1
    error_message = "api_db_connection_budget (${var.api_db_connection_budget}) divided by api_max_replicas (${var.api_max_replicas}) is less than one connection per replica, so the pool size would be floored at 1 and the budget would no longer mean what it says."
  }
}

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
  container_app_id           = module.api_app.container_app_id
  container_app_env_id       = module.vnet.container_app_env_id
  resource_group_name        = azurerm_resource_group.main.name
  alert_email                = var.alert_email
  tags                       = local.tags

  key_vault_id                 = module.key_vault.id
  postgres_server_id           = module.postgres.postgres_server_id
  acs_communication_service_id = var.acs_communication_service_id

  container_app_url        = module.web_app.container_app_url
  mail_delivery_report_key = var.mail_delivery_report_key
}
