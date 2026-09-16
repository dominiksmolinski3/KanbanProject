terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.3"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
    time = {
      source  = "hashicorp/time"
      version = ">= 0.9.0"
    }
  }
  backend "azurerm" {
    resource_group_name  = "tfstate-rg"
    storage_account_name = "tfstatekanban"
    container_name       = "tfstate"
    use_azuread_auth     = true
    // key = "terraform.tfstate" // optional
  }
}

# Every Azure service this deployment uses, so a subscription that has never used one registers it
# rather than the resource failing with `MissingSubscriptionRegistration` (hit for
# Microsoft.EventGrid on the first delivery-report apply - the provider's own defaults didn't cover
# it). Listed exhaustively rather than just the gap, since registering an already-registered
# namespace is a no-op and the list should describe this deployment, not one provider version's
# blind spots. TerraformResourceProvidersRegisteredTest derives the same set from `resource
# "azurerm_*"` blocks and fails the build if they disagree. Microsoft.Resources/Authorization are
# the control plane itself and can't be unregistered, so they're absent deliberately.
provider "azurerm" {
  use_cli         = true
  subscription_id = var.subscription_id

  resource_providers_to_register = [
    "Microsoft.App",                 # container app + its managed environment
    "Microsoft.DBforPostgreSQL",     # flexible server
    "Microsoft.EventGrid",           # mail delivery-report system topic and subscription
    "Microsoft.Insights",            # action group, metric alerts, scheduled query rules, diagnostics
    "Microsoft.KeyVault",            # vault and secrets
    "Microsoft.ManagedIdentity",     # the app's user-assigned identity
    "Microsoft.Network",             # vnet, subnets, NSGs, private endpoints, private DNS
    "Microsoft.OperationalInsights", # log analytics workspace
    "Microsoft.Storage",             # attachment storage account
  ]

  features {
    key_vault {
      purge_soft_delete_on_destroy = var.key_vault_purge_soft_delete_on_destroy
    }
  }
}
