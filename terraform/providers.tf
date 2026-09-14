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

# Every Azure service this deployment touches, named so a subscription that has never used one
# registers it rather than refusing the resource.
#
# A resource provider is registered per *subscription*, and an ARM request against an unregistered
# namespace is refused outright:
#
#   creating System Topic (...): unexpected status 409 (409 Conflict) with error:
#   MissingSubscriptionRegistration: The subscription is not registered to use namespace
#   'Microsoft.EventGrid'.
#
# That is not hypothetical - it is what the first apply of the delivery-report work did, on the one
# environment that exists. Every other namespace here happened to be registered already, because
# something had previously created a resource in it; Event Grid was the first genuinely new service
# added since the subscription was set up, so it was the first to find out. The azurerm provider's
# own default registration set does not cover it.
#
# Listing all of them rather than only the one that failed is deliberate. The alternative is a list
# of "the ones the provider's defaults miss", which is a property of the provider version rather
# than of this deployment, is not readable from here, and changes under a `~> 5.3` bump without
# anything saying so. Registering a namespace that is already registered is a no-op, so an
# exhaustive list costs nothing and says something true: these are the Azure services this
# deployment is made of.
#
# TerraformResourceProvidersRegisteredTest derives this same set from the `resource "azurerm_*"`
# blocks and fails the build when the two disagree - because the cost of forgetting is not a red
# build, it is an apply that dies partway through against a real environment.
#
# Microsoft.Resources and Microsoft.Authorization are deliberately absent: they are the control
# plane itself and cannot be unregistered, so there is nothing for a registration to do.
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
