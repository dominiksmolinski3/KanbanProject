data "azurerm_monitor_diagnostic_categories" "container_app" {
  resource_id = var.container_app_id
}

locals {
  container_app_log_categories    = toset(data.azurerm_monitor_diagnostic_categories.container_app.log_category_types)
  container_app_metric_categories = toset(try(data.azurerm_monitor_diagnostic_categories.container_app.metrics, []))
}

resource "azurerm_monitor_diagnostic_setting" "container_app" {
  name                       = "diag-kanban-app-${var.env}"
  target_resource_id         = var.container_app_id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  dynamic "enabled_log" {
    for_each = local.container_app_log_categories
    content {
      category = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = local.container_app_metric_categories
    content {
      category = enabled_metric.value
    }
  }
}

data "azurerm_monitor_diagnostic_categories" "container_app_env" {
  resource_id = var.container_app_env_id
}

locals {
  container_app_env_log_categories    = toset(data.azurerm_monitor_diagnostic_categories.container_app_env.log_category_types)
  container_app_env_metric_categories = toset(try(data.azurerm_monitor_diagnostic_categories.container_app_env.metrics, []))
}

resource "azurerm_monitor_diagnostic_setting" "container_app_env" {
  name                       = "diag-kanban-cae-${var.env}"
  target_resource_id         = var.container_app_env_id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  dynamic "enabled_log" {
    for_each = local.container_app_env_log_categories
    content {
      category = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = local.container_app_env_metric_categories
    content {
      category = enabled_metric.value
    }
  }
}

resource "azurerm_monitor_action_group" "main" {
  tags                = var.tags
  count               = var.alert_email != "" ? 1 : 0
  name                = "ag-kanban-${var.env}"
  resource_group_name = var.resource_group_name
  short_name          = "kanban${var.env}"

  email_receiver {
    name          = "primary"
    email_address = var.alert_email
  }
}

resource "azurerm_monitor_metric_alert" "container_app_high_cpu" {
  tags                = var.tags
  count               = var.alert_email != "" ? 1 : 0
  name                = "kanban-${var.env}-high-cpu"
  resource_group_name = var.resource_group_name
  scopes              = [var.container_app_id]
  description         = "Average CPU usage is close to the configured limit."
  severity            = 2
  enabled             = true

  frequency   = "PT1M"
  window_size = "PT5M"

  criteria {
    metric_namespace = "Microsoft.App/containerApps"
    metric_name      = "UsageNanoCores"
    aggregation      = "Average"
    operator         = "GreaterThan"
    threshold        = 200000000
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}

resource "azurerm_monitor_metric_alert" "container_app_high_memory" {
  tags                = var.tags
  count               = var.alert_email != "" ? 1 : 0
  name                = "kanban-${var.env}-high-memory"
  resource_group_name = var.resource_group_name
  scopes              = [var.container_app_id]
  description         = "Average memory working set is close to the configured limit."
  severity            = 2
  enabled             = true

  frequency   = "PT1M"
  window_size = "PT5M"

  criteria {
    metric_namespace = "Microsoft.App/containerApps"
    metric_name      = "WorkingSetBytes"
    aggregation      = "Average"
    operator         = "GreaterThan"
    threshold        = 450000000
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}

data "azurerm_monitor_diagnostic_categories" "key_vault" {
  resource_id = var.key_vault_id
}

resource "azurerm_monitor_diagnostic_setting" "key_vault" {
  name                       = "diag-kanban-kv-${var.env}"
  target_resource_id         = var.key_vault_id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  dynamic "enabled_log" {
    for_each = toset(data.azurerm_monitor_diagnostic_categories.key_vault.log_category_types)
    content {
      category = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = toset(try(data.azurerm_monitor_diagnostic_categories.key_vault.metrics, []))
    content {
      category = enabled_metric.value
    }
  }
}

data "azurerm_monitor_diagnostic_categories" "postgres" {
  resource_id = var.postgres_server_id
}

resource "azurerm_monitor_diagnostic_setting" "postgres" {
  name                       = "diag-kanban-psql-${var.env}"
  target_resource_id         = var.postgres_server_id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  dynamic "enabled_log" {
    for_each = toset(data.azurerm_monitor_diagnostic_categories.postgres.log_category_types)
    content {
      category = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = toset(try(data.azurerm_monitor_diagnostic_categories.postgres.metrics, []))
    content {
      category = enabled_metric.value
    }
  }
}

resource "azurerm_monitor_metric_alert" "http_5xx" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-http-5xx"
  resource_group_name = var.resource_group_name
  scopes              = [var.container_app_id]
  description         = "The app is answering server errors. Unlike CPU, this is already visible to a user."
  severity            = 1
  enabled             = true

  frequency   = "PT1M"
  window_size = "PT5M"

  criteria {
    metric_namespace = "Microsoft.App/containerApps"
    metric_name      = "Requests"
    aggregation      = "Total"
    operator         = "GreaterThan"
    threshold        = 5

    dimension {
      name     = "statusCodeCategory"
      operator = "Include"
      values   = ["5xx"]
    }
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}

resource "azurerm_monitor_metric_alert" "container_app_restarts" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-replica-restarts"
  resource_group_name = var.resource_group_name
  scopes              = [var.container_app_id]
  description         = "Replicas are restarting. With max_replicas pinned low this is a crash loop, not a rolling update."
  severity            = 1
  enabled             = true

  frequency   = "PT1M"
  window_size = "PT15M"

  criteria {
    metric_namespace = "Microsoft.App/containerApps"
    metric_name      = "RestartCount"
    aggregation      = "Maximum"
    operator         = "GreaterThan"
    threshold        = 3
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}

resource "azurerm_monitor_metric_alert" "postgres_storage" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-postgres-storage"
  resource_group_name = var.resource_group_name
  scopes              = [var.postgres_server_id]
  description         = "Postgres storage is above 85%. A full volume makes the server read-only, and growing it is not instant."
  severity            = 2
  enabled             = true

  frequency   = "PT5M"
  window_size = "PT15M"

  criteria {
    metric_namespace = "Microsoft.DBforPostgreSQL/flexibleServers"
    metric_name      = "storage_percent"
    aggregation      = "Average"
    operator         = "GreaterThan"
    threshold        = 85
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}

resource "azurerm_monitor_metric_alert" "postgres_connections" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-postgres-failed-connections"
  resource_group_name = var.resource_group_name
  scopes              = [var.postgres_server_id]
  description         = "Connections are being refused - usually the pool exhausting max_connections on a Burstable SKU."
  severity            = 2
  enabled             = true

  frequency   = "PT5M"
  window_size = "PT15M"

  criteria {
    metric_namespace = "Microsoft.DBforPostgreSQL/flexibleServers"
    metric_name      = "connections_failed"
    aggregation      = "Total"
    operator         = "GreaterThan"
    threshold        = 10
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}
