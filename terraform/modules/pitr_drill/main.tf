locals {
  job_name = "kanban-${var.env}-pitr-drill"
}

resource "azurerm_resource_group" "scratch" {
  tags     = var.tags
  name     = "kanban-${var.env}-pitr-rg"
  location = var.location
}

resource "azurerm_user_assigned_identity" "drill" {
  tags                = var.tags
  name                = "kanban-pitr-drill-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "azurerm_role_definition" "scratch" {
  name        = "kanban-${var.env}-pitr-scratch"
  scope       = azurerm_resource_group.scratch.id
  description = "Creates, reads and deletes the scratch servers a point-in-time restore drill makes."

  permissions {
    actions = [
      "Microsoft.DBforPostgreSQL/flexibleServers/read",
      "Microsoft.DBforPostgreSQL/flexibleServers/write",
      "Microsoft.DBforPostgreSQL/flexibleServers/delete",
    ]
  }

  assignable_scopes = [azurerm_resource_group.scratch.id]
}

resource "azurerm_role_definition" "source" {
  name        = "kanban-${var.env}-pitr-source"
  scope       = var.resource_group_id
  description = "Reads the server a drill restores from, and places its copy in the same subnet and DNS zone."

  permissions {
    actions = [
      "Microsoft.DBforPostgreSQL/flexibleServers/read",
      "Microsoft.Network/virtualNetworks/subnets/read",
      "Microsoft.Network/virtualNetworks/subnets/join/action",
      "Microsoft.Network/privateDnsZones/read",
      "Microsoft.Network/privateDnsZones/join/action",
    ]
  }

  assignable_scopes = [var.resource_group_id]
}

resource "azurerm_role_assignment" "scratch" {
  scope              = azurerm_resource_group.scratch.id
  role_definition_id = azurerm_role_definition.scratch.role_definition_resource_id
  principal_id       = azurerm_user_assigned_identity.drill.principal_id
  principal_type     = "ServicePrincipal"
}

resource "azurerm_role_assignment" "source" {
  for_each = {
    server   = var.postgres_server_id
    subnet   = var.postgres_subnet_id
    dns_zone = var.postgres_dns_zone_id
  }

  scope              = each.value
  role_definition_id = azurerm_role_definition.source.role_definition_resource_id
  principal_id       = azurerm_user_assigned_identity.drill.principal_id
  principal_type     = "ServicePrincipal"
}

resource "time_sleep" "wait_for_roles" {
  triggers = {
    scratch = azurerm_role_assignment.scratch.id
    source  = join(",", [for assignment in azurerm_role_assignment.source : assignment.id])
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_container_app_job" "drill" {
  tags                         = var.tags
  name                         = local.job_name
  resource_group_name          = var.resource_group_name
  location                     = var.location
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"

  replica_timeout_in_seconds = 5400
  replica_retry_limit        = 0

  depends_on = [time_sleep.wait_for_roles]

  schedule_trigger_config {
    cron_expression          = var.cron_expression
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.drill.id, var.reader_identity.id]
  }

  template {
    init_container {
      name    = "restore"
      image   = "curlimages/curl:8.16.0@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6"
      cpu     = 0.25
      memory  = "0.5Gi"
      command = ["/bin/sh", "-c", replace(file("${path.module}/pitr-arm.sh"), "\r", ""), "pitr-arm", "restore"]

      env {
        name  = "AZURE_CLIENT_ID"
        value = azurerm_user_assigned_identity.drill.client_id
      }
      env {
        name  = "SCRATCH_GROUP_ID"
        value = azurerm_resource_group.scratch.id
      }
      env {
        name  = "SOURCE_SERVER_ID"
        value = var.postgres_server_id
      }
      env {
        name  = "LOCATION"
        value = var.location
      }
      env {
        name  = "ENV_NAME"
        value = var.env
      }

      volume_mounts {
        name = "drill"
        path = "/drill"
      }
    }

    container {
      name    = "check"
      image   = "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"
      cpu     = 0.25
      memory  = "0.5Gi"
      command = ["/bin/sh", "-c", replace(file("${path.module}/pitr-check.sh"), "\r", "")]

      env {
        name  = "AZURE_CLIENT_ID"
        value = var.reader_identity.client_id
      }
      env {
        name  = "PGUSER"
        value = var.reader_identity.name
      }
      env {
        name  = "PGDATABASE"
        value = var.postgres_database
      }
      env {
        name  = "PGSSLMODE"
        value = "verify-full"
      }
      env {
        name  = "PGSSLROOTCERT"
        value = "system"
      }

      volume_mounts {
        name = "drill"
        path = "/drill"
      }
    }

    container {
      name    = "cleanup"
      image   = "curlimages/curl:8.16.0@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6"
      cpu     = 0.25
      memory  = "0.5Gi"
      command = ["/bin/sh", "-c", replace(file("${path.module}/pitr-arm.sh"), "\r", ""), "pitr-arm", "cleanup"]

      env {
        name  = "AZURE_CLIENT_ID"
        value = azurerm_user_assigned_identity.drill.client_id
      }
      env {
        name  = "SCRATCH_GROUP_ID"
        value = azurerm_resource_group.scratch.id
      }

      volume_mounts {
        name = "drill"
        path = "/drill"
      }
    }

    volume {
      name         = "drill"
      storage_type = "EmptyDir"
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "failed" {
  count                   = var.alerts_enabled ? 1 : 0
  tags                    = var.tags
  name                    = "kanban-${var.env}-pitr-drill-failed"
  resource_group_name     = var.resource_group_name
  location                = var.location
  scopes                  = [var.log_analytics_workspace_id]
  description             = "A point-in-time restore drill started and never confirmed the copy was readable. Check ${azurerm_resource_group.scratch.name} for a scratch server left running."
  severity                = 1
  enabled                 = true
  auto_mitigation_enabled = true

  evaluation_frequency = "PT1H"
  window_duration      = "P2D"

  criteria {
    query                   = <<-KQL
      ContainerAppConsoleLogs_CL
      | where TimeGenerated > ago(2d)
      | where ContainerJobName_s == "${local.job_name}"
      | summarize Started = countif(Log_s has "PITR_START" and TimeGenerated < ago(2h)), Restored = countif(Log_s has "PITR_OK")
      | extend Unconfirmed = Started - Restored
    KQL
    time_aggregation_method = "Maximum"
    metric_measure_column   = "Unconfirmed"
    threshold               = 0
    operator                = "GreaterThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [var.action_group_id]
  }
}
