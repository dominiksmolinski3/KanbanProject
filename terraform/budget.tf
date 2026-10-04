resource "time_static" "budget_start" {
  # Azure refuses a new budget whose start month is already over, so a new scope needs a new start.
  triggers = {
    scope = data.azurerm_subscription.current.id
  }
}

locals {
  budget_resource_groups = compact([
    azurerm_resource_group.main.name,
    module.backup.resource_group_name,
    try(var.acs.resource_group_name, ""),
  ])
}

resource "azurerm_consumption_budget_subscription" "main" {
  count           = var.monthly_budget == null ? 0 : 1
  name            = "budget-kanban-${var.env}"
  subscription_id = data.azurerm_subscription.current.id
  amount          = var.monthly_budget
  time_grain      = "Monthly"

  time_period {
    start_date = formatdate("YYYY-MM-01'T'00:00:00Z", time_static.budget_start.rfc3339)
  }

  filter {
    dimension {
      name   = "ResourceGroupName"
      values = local.budget_resource_groups
    }
  }

  dynamic "notification" {
    for_each = {
      actual-50    = { threshold = 50, type = "Actual" }
      actual-80    = { threshold = 80, type = "Actual" }
      actual-100   = { threshold = 100, type = "Actual" }
      forecast-100 = { threshold = 100, type = "Forecasted" }
    }
    content {
      enabled        = true
      threshold      = notification.value.threshold
      threshold_type = notification.value.type
      operator       = "GreaterThanOrEqualTo"
      contact_roles  = ["Owner"]
      contact_emails = compact([var.alert_email])
    }
  }
}
