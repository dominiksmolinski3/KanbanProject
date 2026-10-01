locals {
  job_name = "kanban-${var.env}-db-roles"
}

resource "azurerm_user_assigned_identity" "admin" {
  tags                = var.tags
  name                = "kanban-db-admin-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "azurerm_postgresql_flexible_server_active_directory_administrator" "admin" {
  server_name         = var.postgres_server_name
  resource_group_name = var.resource_group_name
  tenant_id           = var.tenant_id
  object_id           = azurerm_user_assigned_identity.admin.principal_id
  principal_name      = azurerm_user_assigned_identity.admin.name
  principal_type      = "ServicePrincipal"
}

resource "azurerm_role_assignment" "database_secrets" {
  for_each = toset(["POSTGRES-USER", "POSTGRES-PASSWORD"])

  scope                = "${var.key_vault_id}/secrets/${each.value}"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.admin.principal_id
}

resource "time_sleep" "wait_for_roles" {
  triggers = {
    role_assignment_ids = join(",", [for assignment in azurerm_role_assignment.database_secrets : assignment.id])
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_container_app_job" "roles" {
  tags                         = var.tags
  name                         = local.job_name
  resource_group_name          = var.resource_group_name
  location                     = var.location
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"

  replica_timeout_in_seconds = 600
  replica_retry_limit        = 0

  depends_on = [time_sleep.wait_for_roles, azurerm_postgresql_flexible_server_active_directory_administrator.admin]

  manual_trigger_config {
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.admin.id]
  }

  secret {
    name                = "postgres-user"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "POSTGRES-USER")
    identity            = azurerm_user_assigned_identity.admin.id
  }

  secret {
    name                = "postgres-password"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "POSTGRES-PASSWORD")
    identity            = azurerm_user_assigned_identity.admin.id
  }

  template {
    container {
      name   = "roles"
      image  = "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"
      cpu    = 0.25
      memory = "0.5Gi"
      # A Windows checkout gives the script CRLF endings, and sh reads each \r as part of the command.
      command = ["/bin/sh", "-c", replace(file("${path.module}/roles.sh"), "\r", "")]

      env {
        name  = "AZURE_CLIENT_ID"
        value = azurerm_user_assigned_identity.admin.client_id
      }
      env {
        name  = "ADMIN_ROLE"
        value = azurerm_user_assigned_identity.admin.name
      }
      env {
        name  = "OWNER_ROLE"
        value = var.owner.name
      }
      env {
        name  = "OWNER_OID"
        value = var.owner.principal_id
      }
      env {
        name  = "WRITER_ROLE"
        value = var.writer.name
      }
      env {
        name  = "WRITER_OID"
        value = var.writer.principal_id
      }
      env {
        name  = "READER_ROLE"
        value = var.reader.name
      }
      env {
        name  = "READER_OID"
        value = var.reader.principal_id
      }
      env {
        name  = "PGHOST"
        value = var.postgres_fqdn
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
      env {
        name  = "PASSWORD_LOGIN"
        value = tostring(var.password_login_enabled)
      }
      env {
        name        = "POSTGRES_USER"
        secret_name = "postgres-user"
      }
      env {
        name        = "POSTGRES_PASSWORD"
        secret_name = "postgres-password"
      }
    }
  }
}

resource "terraform_data" "roles" {
  triggers_replace = [
    azurerm_container_app_job.roles.id,
    filesha256("${path.module}/roles.sh"),
    var.owner.principal_id,
    var.writer.principal_id,
    var.reader.principal_id,
  ]

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = replace(file("${path.root}/scripts/run-container-app-job.sh"), "\r", "")
    environment = {
      JOB            = azurerm_container_app_job.roles.name
      RESOURCE_GROUP = var.resource_group_name
    }
  }
}
