# The STOMP broker WebSocketConfig.configureMessageBroker relays to - the one piece of in-JVM state
# the container split left behind. A single replica, deliberately: the point of a shared broker is
# that every API replica relays to the same one, and clustering RabbitMQ for its own redundancy is a
# separate, considerably larger undertaking this deployment does not need at its current scale - the
# same "smallest viable thing" call the Redis SKU and the JDK HTTP client already made elsewhere.
# Losing the broker loses in-flight board/chat frames, not data: nothing here is durable, and a
# reconnecting client re-subscribes and re-reads rather than replaying a queue.
locals {
  app_port = 61613
  app_name = "kanban-broker-${var.env}"

  # The account both WebSocketConfig's client and system logins use - see StompRelayProperties. One
  # account rather than two: RabbitMQ has no notion of a caller identity past the TCP connection its
  # STOMP plugin terminates, so splitting client/system credentials would buy nothing.
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

      # The stock image has the STOMP plugin off. `rabbitmq-plugins enable --offline` needs no
      # running broker, so it runs first and then hands off to the image's own entrypoint - which is
      # what turns RABBITMQ_DEFAULT_USER/PASS below into an actual account, and a custom image built
      # just to bake the plugin in would mean a third image in the CD matrix for one RUN line.
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

      # TCP, not HTTP: there is no health endpoint to ask, only a port that either accepts a
      # connection or does not. The Erlang VM plus the plugin-enable step is slower to come up than
      # the other two apps' JVMs, hence the generous startup threshold.
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

    # Fixed at one: a second, unclustered RabbitMQ would be a second broker nothing relays between,
    # not a second replica of the same one - the exact failure this module exists to remove. Nothing
    # here scales with API replica count, so there is no scale rule.
    min_replicas = 1
    max_replicas = 1
  }

  # Internal only - reachable exclusively from other apps in this Container Apps environment, which
  # is a materially tighter boundary than Postgres's or Redis's own private endpoints (reachable from
  # anywhere in the VNet). TCP ingress needs no exposed_port distinct from target_port here: nothing
  # remaps it, and other apps in the environment reach this one by its name and this port.
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

# Scoped to the one secret, not the vault - the same narrow grant the web app's GHCR-TOKEN reader
# gets, and for the same reason: this identity needs only the password it starts with, not the
# Postgres password or the JWT signing key sitting in the same vault.
resource "azurerm_role_assignment" "rabbitmq_password_reader" {
  scope                = "${var.key_vault_id}/secrets/RABBITMQ-PASSWORD"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.main.principal_id

  depends_on = [azurerm_key_vault_secret.rabbitmq_password]
}

# Keyed on the assignment's id rather than ordered by depends_on alone, so the wait is recreated
# whenever the grant is - the same pattern modules/api_app and modules/web_app use.
resource "time_sleep" "wait_for_secrets_user" {
  triggers = {
    role_assignment_id = azurerm_role_assignment.rabbitmq_password_reader.id
  }
  create_duration = var.rbac_propagation_delay
}

# Generated rather than fixed, and stored the same module-owns-its-secret way REDIS-ACCESS-KEY is:
# api_app reads it back by name through its own, already-broad Key Vault grant rather than through a
# Terraform attribute reference, which is why it needs no direct dependency on this module's resources
# - only the depends_on the root module already adds for postgres, storage and redis.
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
