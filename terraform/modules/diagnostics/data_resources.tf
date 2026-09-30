locals {
  data_resource_diagnostics = {
    blob          = "${var.storage_account_id}/blobServices/default"
    redis         = var.redis_cluster_id
    redis-default = "${var.redis_cluster_id}/databases/default"
  }
}

data "azurerm_monitor_diagnostic_categories" "data_resources" {
  for_each    = local.data_resource_diagnostics
  resource_id = each.value
}

resource "azurerm_monitor_diagnostic_setting" "data_resources" {
  for_each                   = local.data_resource_diagnostics
  name                       = "diag-kanban-${each.key}-${var.env}"
  target_resource_id         = each.value
  log_analytics_workspace_id = var.log_analytics_workspace_id

  dynamic "enabled_log" {
    for_each = toset(data.azurerm_monitor_diagnostic_categories.data_resources[each.key].log_category_types)
    content {
      category = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = toset(try(data.azurerm_monitor_diagnostic_categories.data_resources[each.key].metrics, []))
    content {
      category = enabled_metric.value
    }
  }
}

resource "azurerm_monitor_metric_alert" "postgres_cpu_credits" {
  count               = var.alert_email != "" && var.postgres_burstable ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-postgres-cpu-credits"
  resource_group_name = var.resource_group_name
  scopes              = [var.postgres_server_id]
  description         = "The Burstable Postgres server is running out of CPU credits. At zero it is throttled to its baseline, and slow queries are the first thing anybody notices."
  severity            = 2
  enabled             = true

  frequency   = "PT5M"
  window_size = "PT15M"

  criteria {
    metric_namespace = "Microsoft.DBforPostgreSQL/flexibleServers"
    metric_name      = "cpu_credits_remaining"
    aggregation      = "Average"
    operator         = "LessThan"
    threshold        = var.postgres_cpu_credits_threshold
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}
