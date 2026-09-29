resource "time_static" "budget_start" {}

resource "azurerm_consumption_budget_resource_group" "main" {
  count             = var.monthly_budget == null ? 0 : 1
  name              = "budget-kanban-${var.env}"
  resource_group_id = azurerm_resource_group.main.id
  amount            = var.monthly_budget
  time_grain        = "Monthly"

  time_period {
    start_date = formatdate("YYYY-MM-01'T'00:00:00Z", time_static.budget_start.rfc3339)
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
