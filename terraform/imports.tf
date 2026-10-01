import {
  to = module.mail[0].azurerm_communication_service.main
  id = "/subscriptions/bd68078d-ae5f-42b4-bc20-96d0b52f5b7e/resourceGroups/rg-kanbanproject/providers/Microsoft.Communication/communicationServices/acs-kanbanproject"
}

import {
  to = module.mail[0].azurerm_email_communication_service.main
  id = "/subscriptions/bd68078d-ae5f-42b4-bc20-96d0b52f5b7e/resourceGroups/rg-kanbanproject/providers/Microsoft.Communication/emailServices/kanban-csemail"
}

import {
  to = module.mail[0].azurerm_email_communication_service_domain.azure_managed
  id = "/subscriptions/bd68078d-ae5f-42b4-bc20-96d0b52f5b7e/resourceGroups/rg-kanbanproject/providers/Microsoft.Communication/emailServices/kanban-csemail/domains/AzureManagedDomain"
}

import {
  to = module.mail[0].azurerm_communication_service_email_domain_association.main
  id = "/subscriptions/bd68078d-ae5f-42b4-bc20-96d0b52f5b7e/resourceGroups/rg-kanbanproject/providers/Microsoft.Communication/communicationServices/acs-kanbanproject|/subscriptions/bd68078d-ae5f-42b4-bc20-96d0b52f5b7e/resourceGroups/rg-kanbanproject/providers/Microsoft.Communication/emailServices/kanban-csemail/domains/AzureManagedDomain"
}
