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

#
# The one alert here that reads a log line rather than a metric, because the failure it watches for
# does not have a metric.
#
# A message the mail provider refuses five times ends up as an `email_outbox` row with
# status = 'FAILED', and before the outbox landed that same refusal was a 500 on /api/auth/register
# - which the http_5xx alert above already fires on. Moving the send off the request thread moved
# that signal with it, so this puts it back. What it costs a person who cannot verify their account
# is identical either way; only the thing that notices changed.
#
# It matches OutboxRelay.DEAD_LETTER_MARKER, a token the relay logs precisely so that something
# outside the process can find the line. Matching a phrase from the sentence would work until
# somebody reworded the sentence, at which point the alert would stop firing and nothing would
# fail. DeadLetterAlertTest reads this file and fails the build if the two strings stop agreeing.
#
# ContainerAppConsoleLogs_CL is the app's own stdout: the environment ships it here because
# azurerm_container_app_environment.main sets log_analytics_workspace_id, and this workspace has
# one application writing to it.
#
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

  # ContainerAppConsoleLogs_CL is a custom log table: Azure Monitor only creates its schema once
  # the container app has actually shipped a log line through the diagnostic setting below, so a
  # rule querying it can't validate until both exist and at least one log has landed. Waiting on
  # the diagnostic setting orders this after the plumbing exists; on a genuinely first-ever apply
  # (no prior revision has logged anything yet) the table itself can still be missing for a few
  # minutes after the app starts, and this resource needs a re-apply once it has.
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

#
# Delivery reports, pushed to the application rather than only landing in a log table.
#
# The alert above tells a person when a message bounces. That is the operator's half and it is the
# half that was built first, because it needed no application change at all. What it cannot do is
# tell the application: `email_outbox` has said "the provider took it" since V10 and nothing more,
# so a row for a mail that bounced an hour ago still reads exactly like a row for one that arrived.
#
# Event Grid closes that. Azure publishes a delivery report per recipient naming the message by the
# id the send returned, and MailDeliveryReportController writes it back onto the row that id came
# from. Two resources: a system topic on the Communication Services resource, which is where Azure
# publishes from, and one subscription pointing at this deployment's own webhook.
#
# Three things about the gating are deliberate:
#
#   * It is off unless mail_delivery_report_key is set, exactly like the bounce alert is off unless
#     acs_communication_service_id is. A key nobody has chosen would be a public write endpoint.
#   * The URL is built here from the container app's own FQDN and that same key, rather than being
#     a variable somebody pastes. A URL and a key configured separately are two things that can
#     disagree, and the failure when they do is a subscription that exists and delivers nothing.
#   * Only the delivery-report event type is included. The Communication Services topic also
#     publishes SMS, chat and engagement-tracking events, none of which this endpoint is for, and
#     a subscription that received them would be asking the application to ignore traffic it never
#     needed to see.
#
# What this cannot do is apply before the application is deployed and serving. Event Grid performs
# a validation handshake against the URL at creation time and refuses to create a subscription
# whose endpoint does not answer it - so this resource is the one piece of the deployment with an
# ordering constraint against the app itself, rather than only against other Terraform.
#
# The group the system topic has to live in, which is the ACS resource's own and not this
# deployment's.
#
#   InvalidRequest: System topic resource group must match with source resource group.
#
# The Communication Services resource is created by hand, outside Terraform and outside this
# deployment's resource group - `acs-kanbanproject` sits in `rg-kanbanproject` while dev is
# `kanban-dev-rg` - so `var.resource_group_name` is the one value it is guaranteed not to be.
# Reading the group out of the id Terraform is already given is the same argument the webhook URL
# is built on: a group configured separately from the resource it must match is two things that can
# disagree, and the failure when they do is an apply that stops halfway.
#
# So Terraform writes two resources into a resource group it does not own. That is not a choice
# this module gets to make - Azure refuses any other arrangement - and it is worth knowing before
# a second environment points at the same Communication Services resource, because these two would
# then be sharing a group with another environment's pair.
#
# An ARM id is /subscriptions/<sub>/resourceGroups/<rg>/providers/..., so element 4 of the split is
# the group. `try` keeps a malformed id from failing with a message about a list index instead of
# about the id, and the precondition below says what is actually wrong.
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

    # Azure's own defaults, written down because leaving them out does not mean "leave them alone".
    # Event Grid fills them in at creation - 1 and 64 - and Terraform then reads back two values the
    # configuration does not set, so every subsequent plan proposes setting them to null and every
    # apply puts them back. The result is a deployment that can never report "no changes" again,
    # which is worse than it sounds: `terraform plan` is the only thing this project has that says
    # whether an environment matches its description, and a permanent one-resource diff is how
    # people learn to skim it.
    #
    # One report per delivery attempt is also what the endpoint is written for: the controller
    # takes a batch, but the ordering rule that makes a later report win is per-row and per-clock,
    # not per-batch, so batching buys nothing here and costs the retry granularity.
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
