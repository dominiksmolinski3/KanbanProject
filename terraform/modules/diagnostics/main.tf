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

locals {
  metric_alert_scope = [var.log_analytics_workspace_id]
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "mail_dead_letters" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-mail-dead-letters"
  resource_group_name = var.resource_group_name
  location            = var.location
  scopes              = local.metric_alert_scope
  description         = "The mail relay gave up on a message. Nobody is being told their verification code."
  severity            = 1
  enabled             = true

  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"

  criteria {
    query                   = <<-KQL
      AppMetrics
      | where Name == "kanban_mail_outbox_dead_letters"
      | summarize DeadLetters = sum(Sum)
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "DeadLetters"
    threshold               = 0
    operator                = "GreaterThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.main[0].id]
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "mail_backlog" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-mail-backlog"
  resource_group_name = var.resource_group_name
  location            = var.location
  scopes              = local.metric_alert_scope
  description         = "Outbound mail has been waiting for an hour without the queue emptying once. The relay is stuck or the provider is refusing everything."
  severity            = 2
  enabled             = true

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"

  criteria {
    query                   = <<-KQL
      AppMetrics
      | where Name == "kanban_mail_outbox_pending"
      | summarize Pending = max(Max) by bin(TimeGenerated, 5m)
    KQL
    time_aggregation_method = "Minimum"
    metric_measure_column   = "Pending"
    threshold               = 0
    operator                = "GreaterThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.main[0].id]
  }
}

locals {
  refusal_alerts = {
    auth-rate-limit = {
      metric      = "kanban_auth_ratelimit_refused"
      threshold   = 50
      description = "Over 50 sign-in, signup or reset attempts refused by the rate limiter in 15 minutes - somebody is hammering the credential routes, or a client is retrying in a loop."
    }
    edit-conflicts = {
      metric      = "kanban_task_optimistic_lock_conflicts"
      threshold   = 20
      description = "Over 20 edits refused as conflicting in 15 minutes. Two people racing on one card is normal; this many is a client re-sending stale versions."
    }
    board-subscriptions-dropped = {
      metric      = "kanban_board_subscription_dropped"
      threshold   = 20
      description = "Over 20 board subscriptions dropped in 15 minutes. Either live sync is misconfigured and every screen has quietly stopped updating, or somebody is subscribing to boards that are not theirs."
    }
    attachment-transfers-busy = {
      metric      = "kanban_attachment_transfer_refused"
      threshold   = 10
      description = "Over 10 attachment transfers refused as busy in 15 minutes. The fleet-wide transfer cap is too low for the traffic."
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "refusals" {
  for_each            = var.alert_email != "" ? local.refusal_alerts : {}
  tags                = var.tags
  name                = "kanban-${var.env}-${each.key}"
  resource_group_name = var.resource_group_name
  location            = var.location
  scopes              = local.metric_alert_scope
  description         = each.value.description
  severity            = 3
  enabled             = true

  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"

  criteria {
    query                   = <<-KQL
      AppMetrics
      | where Name == "${each.value.metric}"
      | summarize Refused = sum(Sum)
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "Refused"
    threshold               = each.value.threshold
    operator                = "GreaterThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.main[0].id]
  }
}

data "azurerm_monitor_diagnostic_categories" "acs" {
  count       = var.acs_communication_service_id != "" ? 1 : 0
  resource_id = var.acs_communication_service_id
}

resource "azurerm_monitor_diagnostic_setting" "acs" {
  count                      = var.acs_communication_service_id != "" ? 1 : 0
  name                       = "diag-kanban-acs-${var.env}"
  target_resource_id         = var.acs_communication_service_id
  log_analytics_workspace_id = var.log_analytics_workspace_id

  dynamic "enabled_log" {
    for_each = toset(data.azurerm_monitor_diagnostic_categories.acs[0].log_category_types)
    content {
      category = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = toset(try(data.azurerm_monitor_diagnostic_categories.acs[0].metrics, []))
    content {
      category = enabled_metric.value
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "mail_bounces" {
  count               = var.alert_email != "" && var.acs_communication_service_id != "" && var.mail_delivery_report_key != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-mail-bounces"
  resource_group_name = var.resource_group_name
  location            = var.location
  scopes              = local.metric_alert_scope
  description         = "ACS is reporting a message it accepted did not reach a recipient - a hard bounce, a spam rejection, or an address that does not exist. The application never learns this on its own."
  severity            = 2
  enabled             = true

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"

  criteria {
    query                   = <<-KQL
      AppMetrics
      | where Name == "kanban_mail_delivery_undelivered"
      | summarize Undelivered = sum(Sum)
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "Undelivered"
    threshold               = 0
    operator                = "GreaterThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.main[0].id]
  }
}

locals {
  acs_resource_group = try(split("/", var.acs_communication_service_id)[4], "")
}

resource "azurerm_eventgrid_system_topic" "acs" {
  count = var.acs_communication_service_id != "" && var.mail_delivery_report_key != "" ? 1 : 0
  tags  = var.tags

  name                = "evgt-kanban-acs-${var.env}"
  resource_group_name = local.acs_resource_group
  location            = "global"
  source_resource_id  = var.acs_communication_service_id
  topic_type          = "Microsoft.Communication.CommunicationServices"

  lifecycle {
    precondition {
      condition     = local.acs_resource_group != ""
      error_message = "acs_communication_service_id does not look like an ARM resource id: there is no resourceGroups segment to read the system topic's resource group from."
    }
  }
}

resource "azurerm_eventgrid_system_topic_event_subscription" "mail_delivery_reports" {
  count = var.acs_communication_service_id != "" && var.mail_delivery_report_key != "" ? 1 : 0

  name                = "kanban-${var.env}-mail-delivery-reports"
  system_topic        = azurerm_eventgrid_system_topic.acs[0].name
  resource_group_name = azurerm_eventgrid_system_topic.acs[0].resource_group_name

  included_event_types = ["Microsoft.Communication.EmailDeliveryReportReceived"]

  webhook_endpoint {
    url = "${var.container_app_url}/api/mail/delivery-reports?key=${var.mail_delivery_report_key}"

    max_events_per_batch              = 1
    preferred_batch_size_in_kilobytes = 64
  }

  retry_policy {
    max_delivery_attempts = 10
    event_time_to_live    = 1440
  }
}
