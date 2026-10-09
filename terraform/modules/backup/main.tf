locals {
  job_name       = "kanban-${var.env}-pg-dump"
  drill_job_name = "kanban-${var.env}-restore-drill"
  container      = "pg-dumps"

  retention = {
    daily   = { delete_after = 14, cool_after = null }
    weekly  = { delete_after = 56, cool_after = null }
    monthly = { delete_after = 365, cool_after = 30 }
  }
}

resource "azurerm_resource_group" "backup" {
  tags     = var.tags
  name     = "kanban-${var.env}-backup-rg"
  location = var.location
}

resource "random_string" "suffix" {
  length  = 6
  upper   = false
  lower   = true
  numeric = true
  special = false
}

resource "azurerm_storage_account" "dumps" {
  # checkov:skip=CKV2_AZURE_33: reached through a service endpoint; a private endpoint in another region costs more than the dumps
  tags                = var.tags
  name                = "stkbackup${var.env}${random_string.suffix.result}"
  resource_group_name = azurerm_resource_group.backup.name
  location            = azurerm_resource_group.backup.location

  account_tier             = "Standard"
  account_kind             = "StorageV2"
  account_replication_type = "GRS"
  access_tier              = "Hot"

  https_traffic_only_enabled      = true
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  local_user_enabled              = false
  sftp_enabled                    = false
  default_to_oauth_authentication = true

  public_network_access = "Enabled"

  network_rules {
    default_action             = "Deny"
    bypass                     = ["AzureServices"]
    virtual_network_subnet_ids = compact([var.writer_subnet_id, var.monitoring_subnet_id])
  }

  blob_properties {
    delete_retention_policy {
      days = local.retention.daily.delete_after
    }

    container_delete_retention_policy {
      days = local.retention.daily.delete_after
    }
  }

  sas_policy {
    expiration_period = "0.01:00:00"
    expiration_action = "Log"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_management_lock" "dumps" {
  name       = "lock-${azurerm_storage_account.dumps.name}"
  scope      = azurerm_storage_account.dumps.id
  lock_level = "CanNotDelete"
  notes      = "The database's only copy outside its own server. Remove the lock first to delete the account on purpose."
}

resource "azurerm_monitor_diagnostic_setting" "dumps" {
  name                       = "diag-kanban-backup-blob-${var.env}"
  target_resource_id         = "${azurerm_storage_account.dumps.id}/blobServices/default"
  log_analytics_workspace_id = var.log_analytics_workspace_id

  enabled_log {
    category = "StorageRead"
  }
  enabled_log {
    category = "StorageWrite"
  }
  enabled_log {
    category = "StorageDelete"
  }
}

resource "azurerm_storage_container" "dumps" {
  # checkov:skip=CKV2_AZURE_21: the check wants classic storage insights, which needs the account key; the diagnostic setting above logs reads instead
  name                  = local.container
  storage_account_id    = azurerm_storage_account.dumps.id
  container_access_type = "private"
}

resource "azurerm_storage_container_immutability_policy" "dumps" {
  storage_container_resource_manager_id = azurerm_storage_container.dumps.id
  immutability_period_in_days           = local.retention.daily.delete_after
  locked                                = true
}

resource "azurerm_storage_management_policy" "dumps" {
  storage_account_id = azurerm_storage_account.dumps.id

  dynamic "rule" {
    for_each = var.monitoring_principal_id == null ? [] : [1]
    content {
      name    = "monitoring-retention"
      enabled = true

      filters {
        blob_types   = ["blockBlob"]
        prefix_match = ["monitoring/"]
      }

      actions {
        base_blob {
          delete_after_days_since_creation_greater_than = 7
        }
      }
    }
  }

  dynamic "rule" {
    for_each = local.retention
    content {
      name    = "${rule.key}-retention"
      enabled = true

      filters {
        blob_types   = ["blockBlob"]
        prefix_match = ["${local.container}/${rule.key}/"]
      }

      actions {
        base_blob {
          tier_to_cool_after_days_since_creation_greater_than = rule.value.cool_after
          delete_after_days_since_creation_greater_than       = rule.value.delete_after
        }
      }
    }
  }
}

resource "azurerm_role_definition" "dump_writer" {
  name        = "kanban-${var.env}-backup-writer"
  scope       = azurerm_resource_group.backup.id
  description = "Adds blobs to the dump container. No read, no delete, no overwrite."

  permissions {
    data_actions = [
      "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write",
      "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/add/action",
    ]
  }

  assignable_scopes = [azurerm_resource_group.backup.id]
}

resource "azurerm_storage_container" "monitoring" {
  # checkov:skip=CKV2_AZURE_21: the check wants classic storage insights, which needs the account key; the diagnostic setting above logs reads instead
  count                 = var.monitoring_principal_id == null ? 0 : 1
  name                  = "monitoring"
  storage_account_id    = azurerm_storage_account.dumps.id
  container_access_type = "private"
}

resource "azurerm_role_assignment" "monitoring_writer" {
  count              = var.monitoring_principal_id == null ? 0 : 1
  scope              = azurerm_storage_container.monitoring[0].id
  role_definition_id = azurerm_role_definition.dump_writer.role_definition_resource_id
  principal_id       = var.monitoring_principal_id
}

resource "azurerm_user_assigned_identity" "job" {
  tags                = var.tags
  name                = "kanban-backup-identity-${var.env}"
  location            = var.job_location
  resource_group_name = var.job_resource_group_name
}

resource "azurerm_role_assignment" "dump_writer" {
  scope              = azurerm_storage_container.dumps.id
  role_definition_id = azurerm_role_definition.dump_writer.role_definition_resource_id
  principal_id       = azurerm_user_assigned_identity.job.principal_id
}

resource "time_sleep" "wait_for_roles" {
  triggers = {
    role_assignment_id = azurerm_role_assignment.dump_writer.id
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_container_app_job" "pg_dump" {
  tags                         = var.tags
  name                         = local.job_name
  resource_group_name          = var.job_resource_group_name
  location                     = var.job_location
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"

  replica_timeout_in_seconds = 1800
  replica_retry_limit        = 1

  depends_on = [time_sleep.wait_for_roles]

  schedule_trigger_config {
    cron_expression          = var.cron_expression
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.job.id]
  }

  template {
    init_container {
      name    = "pg-dump"
      image   = "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"
      cpu     = 0.25
      memory  = "0.5Gi"
      command = ["/bin/sh", "-c", replace(file("${path.module}/pg-dump.sh"), "\r", "")]

      env {
        name  = "PGHOST"
        value = var.postgres_fqdn
      }
      env {
        name  = "PGDATABASE"
        value = var.postgres_database
      }
      env {
        name  = "PGUSER"
        value = azurerm_user_assigned_identity.job.name
      }
      env {
        name  = "AZURE_CLIENT_ID"
        value = azurerm_user_assigned_identity.job.client_id
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
        name = "backup"
        path = "/backup"
      }
    }

    container {
      name   = "upload"
      image  = "curlimages/curl:8.16.0@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6"
      cpu    = 0.25
      memory = "0.5Gi"
      # A Windows checkout gives the script CRLF endings, and sh reads each \r as part of the command.
      command = ["/bin/sh", "-c", replace(file("${path.module}/pg-dump-upload.sh"), "\r", "")]

      env {
        name  = "AZURE_CLIENT_ID"
        value = azurerm_user_assigned_identity.job.client_id
      }
      env {
        name  = "CONTAINER_URL"
        value = "${azurerm_storage_account.dumps.primary_blob_endpoint}${local.container}"
      }

      volume_mounts {
        name = "backup"
        path = "/backup"
      }
    }

    volume {
      name         = "backup"
      storage_type = "EmptyDir"
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "stale" {
  count                   = var.alerts_enabled ? 1 : 0
  tags                    = var.tags
  name                    = "kanban-${var.env}-backup-stale"
  resource_group_name     = var.job_resource_group_name
  location                = var.job_location
  scopes                  = [var.log_analytics_workspace_id]
  description             = "No database dump has reached the backup account in 26 hours. The nightly job failed, or stopped being scheduled."
  severity                = 1
  enabled                 = true
  auto_mitigation_enabled = true

  evaluation_frequency = "PT1H"
  window_duration      = "P2D"

  criteria {
    query                   = <<-KQL
      ContainerAppConsoleLogs_CL
      | where TimeGenerated > ago(26h)
      | where ContainerJobName_s == "${local.job_name}" and Log_s has "BACKUP_OK"
      | summarize Succeeded = count()
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "Succeeded"
    threshold               = 1
    operator                = "LessThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [var.action_group_id]
  }
}

resource "azurerm_user_assigned_identity" "drill" {
  tags                = var.tags
  name                = "kanban-restore-drill-identity-${var.env}"
  location            = var.job_location
  resource_group_name = var.job_resource_group_name
}

resource "azurerm_role_assignment" "drill_reader" {
  scope                = azurerm_storage_container.dumps.id
  role_definition_name = "Storage Blob Data Reader"
  principal_id         = azurerm_user_assigned_identity.drill.principal_id
}

resource "time_sleep" "wait_for_drill_role" {
  triggers = {
    role_assignment_id = azurerm_role_assignment.drill_reader.id
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_container_app_job" "restore_drill" {
  tags                         = var.tags
  name                         = local.drill_job_name
  resource_group_name          = var.job_resource_group_name
  location                     = var.job_location
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"

  replica_timeout_in_seconds = 1800
  replica_retry_limit        = 1

  depends_on = [time_sleep.wait_for_drill_role]

  schedule_trigger_config {
    cron_expression          = var.drill_cron_expression
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.drill.id]
  }

  template {
    init_container {
      name    = "download"
      image   = "curlimages/curl:8.16.0@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6"
      cpu     = 0.25
      memory  = "0.5Gi"
      command = ["/bin/sh", "-c", replace(file("${path.module}/restore-drill-download.sh"), "\r", "")]

      env {
        name  = "AZURE_CLIENT_ID"
        value = azurerm_user_assigned_identity.drill.client_id
      }
      env {
        name  = "CONTAINER_URL"
        value = "${azurerm_storage_account.dumps.primary_blob_endpoint}${local.container}"
      }

      volume_mounts {
        name = "backup"
        path = "/backup"
      }
    }

    container {
      name    = "restore"
      image   = "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"
      cpu     = 0.5
      memory  = "1Gi"
      command = ["/bin/sh", "-c", replace(file("${path.module}/restore-drill.sh"), "\r", "")]

      volume_mounts {
        name = "backup"
        path = "/backup"
      }
    }

    volume {
      name         = "backup"
      storage_type = "EmptyDir"
    }
  }
}

resource "azurerm_monitor_scheduled_query_rules_alert_v2" "drill_failed" {
  count                   = var.alerts_enabled ? 1 : 0
  tags                    = var.tags
  name                    = "kanban-${var.env}-restore-drill-failed"
  resource_group_name     = var.job_resource_group_name
  location                = var.job_location
  scopes                  = [var.log_analytics_workspace_id]
  description             = "The newest database dump has not restored into a scratch Postgres in 26 hours. The dumps may exist and still be unusable."
  severity                = 1
  enabled                 = true
  auto_mitigation_enabled = true

  evaluation_frequency = "PT1H"
  window_duration      = "P2D"

  criteria {
    query                   = <<-KQL
      ContainerAppConsoleLogs_CL
      | where TimeGenerated > ago(26h)
      | where ContainerJobName_s == "${local.drill_job_name}" and Log_s has "RESTORE_OK"
      | summarize Restored = count()
    KQL
    time_aggregation_method = "Total"
    metric_measure_column   = "Restored"
    threshold               = 1
    operator                = "LessThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [var.action_group_id]
  }
}
