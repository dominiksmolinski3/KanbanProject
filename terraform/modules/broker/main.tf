locals {
  app_port = 61613
  app_name = "kanban-broker-${var.env}"

  username = "kanban"
}

resource "azurerm_container_app" "main" {
  tags                         = var.tags
  name                         = local.app_name
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_env_id
  workload_profile_name        = "Consumption"
  revision_mode                = "Single"

  depends_on = [time_sleep.wait_for_secrets_user]

  secret {
    name                = "rabbitmq-password"
    key_vault_secret_id = format("%s/secrets/%s", trimsuffix(var.key_vault_uri, "/"), "RABBITMQ-PASSWORD")
    identity            = azurerm_user_assigned_identity.main.id
  }

  template {
    container {
      name   = "rabbitmq"
      image  = "rabbitmq:4-alpine"
      cpu    = 0.5
      memory = "1Gi"

      command = ["sh", "-c"]
      args    = ["rabbitmq-plugins enable --offline rabbitmq_stomp && exec docker-entrypoint.sh rabbitmq-server"]

      env {
        name  = "RABBITMQ_DEFAULT_USER"
        value = local.username
      }
      env {
        name        = "RABBITMQ_DEFAULT_PASS"
        secret_name = "rabbitmq-password"
      }

      startup_probe {
        transport               = "TCP"
        port                    = local.app_port
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 30
      }

      readiness_probe {
        transport               = "TCP"
        port                    = local.app_port
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 3
        success_count_threshold = 1
      }

      liveness_probe {
        transport               = "TCP"
        port                    = local.app_port
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }
    }

    min_replicas = 1
    max_replicas = 1
  }

  ingress {
    external_enabled = false
    target_port      = local.app_port
    transport        = "tcp"

    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.main.id]
  }
}

resource "azurerm_user_assigned_identity" "main" {
  tags                = var.tags
  name                = "kanban-broker-identity-${var.env}"
  location            = var.location
  resource_group_name = var.resource_group_name
}

resource "azurerm_role_assignment" "rabbitmq_password_reader" {
  scope                = "${var.key_vault_id}/secrets/RABBITMQ-PASSWORD"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.main.principal_id

  depends_on = [azurerm_key_vault_secret.rabbitmq_password]
}

resource "time_sleep" "wait_for_secrets_user" {
  triggers = {
    role_assignment_id = azurerm_role_assignment.rabbitmq_password_reader.id
  }
  create_duration = var.rbac_propagation_delay
}

resource "random_password" "rabbitmq_password" {
  length  = 32
  special = false
}

resource "azurerm_key_vault_secret" "rabbitmq_password" {
  tags         = var.tags
  name         = "RABBITMQ-PASSWORD"
  value        = random_password.rabbitmq_password.result
  content_type = "RabbitMQ STOMP relay password"
  key_vault_id = var.key_vault_id
}
