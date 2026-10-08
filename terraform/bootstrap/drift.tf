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
  subject                   = "${var.github_oidc_subject_prefix}:environment:${var.drift_environment}"
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

data "azurerm_resources" "vault" {
  resource_group_name = var.environment_resource_groups[0]
  type                = "Microsoft.KeyVault/vaults"
}

resource "azurerm_role_assignment" "drift_secrets" {
  scope                = one(data.azurerm_resources.vault.resources).id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.drift.principal_id
  principal_type       = "ServicePrincipal"
}

data "azuread_application_published_app_ids" "well_known" {}

data "azuread_service_principal" "microsoft_graph" {
  client_id = data.azuread_application_published_app_ids.well_known.result["MicrosoftGraph"]
}

resource "azuread_app_role_assignment" "drift_application_reader" {
  app_role_id         = data.azuread_service_principal.microsoft_graph.app_role_ids["Application.Read.All"]
  principal_object_id = azurerm_user_assigned_identity.drift.principal_id
  resource_object_id  = data.azuread_service_principal.microsoft_graph.object_id
}
