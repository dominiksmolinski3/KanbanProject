plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "azurerm" {
  enabled = true
  version = "0.32.0"
  source  = "github.com/terraform-linters/tflint-ruleset-azurerm"
}

# Child modules take their Terraform and provider versions from the root, which pins both.
rule "terraform_required_version" {
  enabled = false
}

rule "terraform_required_providers" {
  enabled = false
}

# The environment is destroyed and rebuilt on purpose; the backups, not the state, keep the data.
rule "azurerm_resources_missing_prevent_destroy" {
  enabled = false
}
