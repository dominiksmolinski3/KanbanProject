terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.46"
    }
    http = {
      source  = "hashicorp/http"
      version = ">= 3.4.0"
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

provider "azurerm" {
  use_cli = true
  subscription_id = var.subscription_id
  features {}
}

provider "http" {}