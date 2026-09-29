locals {
  # managed_by is left out: a resource made in the portal must not claim Terraform owns it.
  inherited_tags = toset(["application", "environment", "owner", "repository"])
}

data "azurerm_subscription" "current" {}

data "azurerm_policy_definition" "inherit_tag_if_missing" {
  display_name = "Inherit a tag from the resource group if missing"
}

resource "azurerm_resource_group_policy_assignment" "inherit_tag" {
  for_each             = local.inherited_tags
  name                 = "inherit-tag-${each.key}"
  display_name         = "Inherit the ${each.key} tag from the resource group"
  resource_group_id    = azurerm_resource_group.main.id
  policy_definition_id = data.azurerm_policy_definition.inherit_tag_if_missing.id
  location             = azurerm_resource_group.main.location

  parameters = jsonencode({
    tagName = { value = each.key }
  })

  identity {
    type = "SystemAssigned"
  }
}

resource "azurerm_role_assignment" "inherit_tag" {
  for_each = {
    for pair in setproduct(local.inherited_tags, data.azurerm_policy_definition.inherit_tag_if_missing.role_definition_ids) :
    "${pair[0]}/${basename(pair[1])}" => { tag = pair[0], role = pair[1] }
  }
  scope              = azurerm_resource_group.main.id
  role_definition_id = "${data.azurerm_subscription.current.id}${each.value.role}"
  principal_id       = azurerm_resource_group_policy_assignment.inherit_tag[each.value.tag].identity[0].principal_id
}

resource "time_sleep" "wait_for_inherit_tag_roles" {
  triggers = {
    role_assignment_ids = join(",", sort([for r in azurerm_role_assignment.inherit_tag : r.id]))
  }
  create_duration = var.rbac_propagation_delay
}

resource "azurerm_resource_group_policy_remediation" "inherit_tag" {
  for_each             = local.inherited_tags
  name                 = "inherit-tag-${each.key}"
  resource_group_id    = azurerm_resource_group.main.id
  policy_assignment_id = azurerm_resource_group_policy_assignment.inherit_tag[each.key].id
  # A new assignment has no compliance data yet, so the default mode finds nothing to fix.
  resource_discovery_mode = "ReEvaluateCompliance"

  depends_on = [time_sleep.wait_for_inherit_tag_roles]
}
