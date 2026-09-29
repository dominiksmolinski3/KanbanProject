locals {
  other_container_apps = {
    web    = var.web_container_app_id
    broker = var.broker_container_app_id
  }
}

data "azurerm_monitor_diagnostic_categories" "other_container_apps" {
  for_each    = local.other_container_apps
  resource_id = each.value
}

resource "azurerm_monitor_diagnostic_setting" "other_container_apps" {
  for_each                   = local.other_container_apps
  name                       = "diag-kanban-${each.key}-${var.env}"
  target_resource_id         = each.value
  log_analytics_workspace_id = var.log_analytics_workspace_id

  dynamic "enabled_log" {
    for_each = toset(data.azurerm_monitor_diagnostic_categories.other_container_apps[each.key].log_category_types)
    content {
      category = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = toset(try(data.azurerm_monitor_diagnostic_categories.other_container_apps[each.key].metrics, []))
    content {
      category = enabled_metric.value
    }
  }
}

resource "azurerm_monitor_metric_alert" "other_container_app_restarts" {
  for_each            = var.alert_email != "" ? local.other_container_apps : {}
  tags                = var.tags
  name                = "kanban-${var.env}-${each.key}-replica-restarts"
  resource_group_name = var.resource_group_name
  scopes              = [each.value]
  description         = "The ${each.key} app's replicas are restarting. A crash-looping broker stops live sync and chat on every screen; a crash-looping edge takes the whole site down."
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

resource "azurerm_monitor_metric_alert" "web_http_5xx" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-web-http-5xx"
  resource_group_name = var.resource_group_name
  scopes              = [var.web_container_app_id]
  description         = "The edge is answering server errors. A 502 from nginx that never reached the API shows up here and nowhere else."
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

resource "azurerm_application_insights_standard_web_test" "public_origin" {
  tags                    = var.tags
  name                    = "kanban-${var.env}-public-origin"
  resource_group_name     = var.resource_group_name
  location                = var.location
  application_insights_id = var.app_insights_id
  description             = "Fetches the published contract through the public edge, so a pass needs the ingress, nginx, the internal hop and the API all answering."
  frequency               = 300
  timeout                 = 30
  enabled                 = true
  retry_enabled           = true

  geo_locations = [
    "emea-nl-ams-azr",
    "emea-gb-db3-azr",
    "emea-fr-pra-edge",
  ]

  request {
    url = "${var.container_app_url}/v3/api-docs"
  }

  validation_rules {
    expected_status_code        = 200
    ssl_check_enabled           = true
    ssl_cert_remaining_lifetime = 7

    content {
      content_match      = "\"openapi\""
      pass_if_text_found = true
    }
  }
}

resource "azurerm_monitor_metric_alert" "public_origin_availability" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-public-origin-down"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_application_insights_standard_web_test.public_origin.id, var.app_insights_id]
  description         = "The public origin is failing from two or more test locations."
  severity            = 0
  enabled             = true

  application_insights_web_test_location_availability_criteria {
    web_test_id           = azurerm_application_insights_standard_web_test.public_origin.id
    component_id          = var.app_insights_id
    failed_location_count = 2
  }

  action {
    action_group_id = azurerm_monitor_action_group.main[0].id
  }
}
