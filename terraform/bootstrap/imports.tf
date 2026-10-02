import {
  to = azurerm_storage_account.state
  id = "/subscriptions/${data.azurerm_subscription.current.subscription_id}/resourceGroups/tfstate-rg/providers/Microsoft.Storage/storageAccounts/tfstatekanban"
}

import {
  to = azurerm_storage_container.state
  id = "/subscriptions/${data.azurerm_subscription.current.subscription_id}/resourceGroups/tfstate-rg/providers/Microsoft.Storage/storageAccounts/tfstatekanban/blobServices/default/containers/tfstate"
}
