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

# The one alert here that reads a log line rather than a metric - the failure it watches has no
# metric. A message refused 5 times becomes an `email_outbox` row with status = 'FAILED' (before the
# outbox this was a 500 on /api/auth/register, which http_5xx already covers); moving the send off
# the request thread moved that signal with it.
#
# Matches OutboxRelay.DEAD_LETTER_MARKER (MAIL_DEAD_LETTER), a token rather than a log phrase so a
# reworded sentence can't silently stop the match. DeadLetterAlertTest pins the two strings together.
#
# ContainerAppConsoleLogs_CL is the app's own stdout, shipped here by the diagnostic setting below.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "mail_dead_letters" {
  count               = var.alert_email != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-mail-dead-letters"
  resource_group_name = var.resource_group_name
  location            = var.location
  scopes              = [var.log_analytics_workspace_id]
  description         = "The mail relay gave up on a message. Nobody is being told their verification code."
  severity            = 1
  enabled             = true

  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"

  criteria {
    query                   = <<-KQL
      ContainerAppConsoleLogs_CL
      | where Log_s contains "MAIL_DEAD_LETTER"
    KQL
    time_aggregation_method = "Count"
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

  # ContainerAppConsoleLogs_CL is a custom log table Azure Monitor creates only after the app has
  # shipped a log line through the diagnostic setting - so this depends on that setting, and a
  # genuinely first-ever apply may still need a re-apply once the table exists.
  depends_on = [azurerm_monitor_diagnostic_setting.container_app]
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
  count               = var.alert_email != "" && var.acs_communication_service_id != "" ? 1 : 0
  tags                = var.tags
  name                = "kanban-${var.env}-mail-bounces"
  resource_group_name = var.resource_group_name
  location            = var.location
  scopes              = [var.log_analytics_workspace_id]
  description         = "ACS is reporting a message it accepted did not reach a recipient - a hard bounce, a spam rejection, or an address that does not exist. The application never learns this on its own."
  severity            = 2
  enabled             = true

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"

  criteria {
    query                   = <<-KQL
      ACSEmailStatusUpdateOperational
      | where DeliveryStatus in ("Bounced", "Failed", "Quarantined", "FilteredSpam", "Suppressed")
    KQL
    time_aggregation_method = "Count"
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

  depends_on = [azurerm_monitor_diagnostic_setting.acs]
}

# Delivery reports: closes the gap where email_outbox only ever knew "the provider took it" (V10),
# never what happened after. Event Grid publishes a report per recipient naming the message by the
# id EmailSender.send returns, and MailDeliveryReportController writes the outcome onto that row.
#
# Gating is deliberate: off unless mail_delivery_report_key is set (an unchosen key would be a
# public write endpoint); the webhook URL is assembled here from the container app's own FQDN and
# that same key rather than pasted in, so they can't be configured into disagreeing; and only the
# delivery-report event type is included, not the ACS topic's SMS/chat/engagement-tracking events.
#
# Ordering constraint against the app itself, not just other Terraform: Event Grid validates the
# endpoint at creation and refuses a subscription that doesn't answer, so the app must already be
# deployed and serving.
#
# The system topic must live in the ACS resource's own resource group, not this deployment's -
# Azure rejects anything else ("System topic resource group must match with source resource group").
# The Communication Services resource is created by hand outside this deployment's group, so both
# Event Grid resources land in a group Terraform does not own; a second environment sharing the same
# ACS resource would share this group with its pair too. `try` on the id's 4th segment (the group)
# avoids a "list index" error on a malformed id in favor of the precondition's own message.
locals {
  acs_resource_group = try(split("/", var.acs_communication_service_id)[4], "")
}

resource "azurerm_eventgrid_system_topic" "acs" {
  count = var.acs_communication_service_id != "" && var.mail_delivery_report_key != "" ? 1 : 0
  tags  = var.tags

  name                = "evgt-kanban-acs-${var.env}"
  resource_group_name = local.acs_resource_group
  # Communication Services is a global resource and its system topic has to match it. A regional
  # location here is rejected at apply time with a message that does not say so.
  location           = "global"
  source_resource_id = var.acs_communication_service_id
  topic_type         = "Microsoft.Communication.CommunicationServices"

  lifecycle {
    precondition {
      condition     = local.acs_resource_group != ""
      error_message = "acs_communication_service_id does not look like an ARM resource id: there is no resourceGroups segment to read the system topic's resource group from."
    }
  }
}

resource "azurerm_eventgrid_system_topic_event_subscription" "mail_delivery_reports" {
  count = var.acs_communication_service_id != "" && var.mail_delivery_report_key != "" ? 1 : 0

  name         = "kanban-${var.env}-mail-delivery-reports"
  system_topic = azurerm_eventgrid_system_topic.acs[0].name
  # The subscription addresses the topic, so it takes the topic's group for the same reason.
  resource_group_name = azurerm_eventgrid_system_topic.acs[0].resource_group_name

  included_event_types = ["Microsoft.Communication.EmailDeliveryReportReceived"]

  webhook_endpoint {
    url = "${var.container_app_url}/api/mail/delivery-reports?key=${var.mail_delivery_report_key}"

    # Azure's own defaults (1, 64), written down explicitly - leaving them out doesn't mean "leave
    # them alone": Event Grid fills them in at creation, so Terraform would read back values the
    # config never set and every plan would propose nulling them, forever. One report per attempt
    # also matches the endpoint: the last-report-wins rule is per-row and per-clock, not per-batch.
    max_events_per_batch              = 1
    preferred_batch_size_in_kilobytes = 64
  }

  # Event Grid's own retry, which is why the endpoint answers 2xx to a report it cannot place: a
  # non-2xx here buys the same report delivered again on this schedule and ignored again each time.
  retry_policy {
    max_delivery_attempts = 10
    event_time_to_live    = 1440
  }
}
