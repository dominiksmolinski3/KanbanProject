terraform {
  required_version = "~> 1.12"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.3"
    }
  }

  # The account this root manages also holds its state; it was made by hand before either existed.
  backend "azurerm" {
    resource_group_name  = "tfstate-rg"
    storage_account_name = "tfstatekanban"
    container_name       = "tfstate"
    key                  = "bootstrap/terraform.tfstate"
    use_azuread_auth     = true
  }
}

provider "azurerm" {
  use_cli         = true
  subscription_id = var.subscription_id

  resource_providers_to_register = [
    "Microsoft.ManagedIdentity",
    "Microsoft.Storage",
  ]

  features {}
}
