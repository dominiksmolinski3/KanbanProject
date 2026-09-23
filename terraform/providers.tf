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
  }
}

provider "azurerm" {
  use_cli         = true
  subscription_id = var.subscription_id

  resource_providers_to_register = [
    "Microsoft.App",
    "Microsoft.Cache",
    "Microsoft.DBforPostgreSQL",
    "Microsoft.EventGrid",
    "Microsoft.Insights",
    "Microsoft.KeyVault",
    "Microsoft.ManagedIdentity",
    "Microsoft.Network",
    "Microsoft.OperationalInsights",
    "Microsoft.Storage",
  ]

  features {
    key_vault {
      purge_soft_delete_on_destroy = var.key_vault_purge_soft_delete_on_destroy
    }
  }
}
