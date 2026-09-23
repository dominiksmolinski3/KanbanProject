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

# The alerts below read the API's own meters, which the Application Insights agent in the backend
# image exports into this workspace's AppMetrics table (see azurerm_application_insights in the root
# module). Three things about that table decide how every query here is written:
#
# - Names arrive with dots turned into underscores: OutboxRelay's kanban.mail.outbox.dead_letters is
#   kanban_mail_outbox_dead_letters here. MetricAlertsMatchTheMetersTest reads every name these
#   queries use and fails the build when one is not a meter the code registers - the same guard
#   DeadLetterAlertTest was for the log marker, now over a name the compiler at least sees once.
# - A counter arrives as its increase over each export interval, one row per replica per series,
#   so sum(Sum) over a window is how many happened in it across the fleet.
# - A gauge arrives as its current value from every replica, and the outbox gauge counts the same
#   table from each of them, so it is read with max() rather than summed.
#
# They replace two log alerts. The dead-letter rule matched the text MAIL_DEAD_LETTER in the console
# log; the bounce rule repeated the list of undelivered statuses in KQL. Both couplings are gone:
# which statuses count is decided in MailDeliveryStatuses alone, and a reworded log line cannot
# silence anything.
locals {
  metric_alert_scope = [var.log_analytics_workspace_id]
}

# A message refused five times becomes a FAILED outbox row, and before the outbox that was a 500 on
# signup the http_5xx rule already covered. Any one is worth a page: it is somebody's verification
# code that is never coming.
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

# What no log line could say: mail piling up before anything has dead-lettered. The relay drains
# fifty rows a minute, so a healthy queue touches zero between signups. One refused message is not
# enough to fire this - its retries (1, 2, 4, 8, then 16 minutes apart) keep it PENDING for about half
# an hour and the dead-letter rule above is what answers it - so the window is an hour, and the
# rule fires only when not one 5-minute bin in it reached an empty queue.
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

# Rates rather than events: each of these happens in ordinary use, and a page per occurrence would
# be a page nobody reads. Severity 3, and a threshold per rule for what "not ordinary" means.
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

# A message ACS accepted and then could not deliver, as the delivery-report webhook tells the API.
# It needs that webhook, so it is gated on the same key the Event Grid subscription below is: with no
# reports flowing, the counter never moves and the rule would only ever say "fine".
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
