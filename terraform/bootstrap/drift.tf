resource "azurerm_user_assigned_identity" "drift" {
  name                = "id-kanban-terraform-drift"
  resource_group_name = data.azurerm_resource_group.state.name
  location            = azurerm_storage_account.state.location
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "drift" {
  name                      = "github-${var.drift_environment}"
  user_assigned_identity_id = azurerm_user_assigned_identity.drift.id
  issuer                    = "https://token.actions.githubusercontent.com"
  subject                   = "repo:${var.github_repository}:environment:${var.drift_environment}"
  audience                  = ["api://AzureADTokenExchange"]
}

resource "azurerm_role_assignment" "drift_reader" {
  scope                = data.azurerm_subscription.current.id
  role_definition_name = "Reader"
  principal_id         = azurerm_user_assigned_identity.drift.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "drift_state_reader" {
  scope                = azurerm_storage_container.state.id
  role_definition_name = "Storage Blob Data Reader"
  principal_id         = azurerm_user_assigned_identity.drift.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_definition" "drift_refresh" {
  name        = "Kanban Terraform drift refresh"
  scope       = data.azurerm_subscription.current.id
  description = "What a terraform plan of this repository calls beyond Reader, plus opening the vault firewall to the runner."

  permissions {
    actions = var.drift_refresh_actions
  }

  assignable_scopes = [data.azurerm_subscription.current.id]
}

resource "azurerm_role_assignment" "drift_refresh" {
  for_each           = toset(var.environment_resource_groups)
  scope              = "${data.azurerm_subscription.current.id}/resourceGroups/${each.value}"
  role_definition_id = azurerm_role_definition.drift_refresh.role_definition_resource_id
  principal_id       = azurerm_user_assigned_identity.drift.principal_id
  principal_type     = "ServicePrincipal"
}

resource "azurerm_role_assignment" "drift_secrets" {
  scope                = "${data.azurerm_subscription.current.id}/resourceGroups/${var.environment_resource_groups[0]}"
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.drift.principal_id
  principal_type       = "ServicePrincipal"
}
