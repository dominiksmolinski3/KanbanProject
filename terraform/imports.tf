# Delete after the first apply that imports these: where the objects do not exist yet, an import fails the plan.
import {
  for_each = var.custom_domain == null ? {} : { certificate = var.custom_domain }
  to       = module.web_app.azurerm_container_app_environment_managed_certificate.custom_domain[0]
  id       = "${module.vnet.container_app_env_id}/managedCertificates/${each.value.certificate_name}"
}

import {
  for_each = var.custom_domain == null ? {} : { binding = var.custom_domain }
  to       = module.web_app.azurerm_container_app_custom_domain.main[0]
  id       = "${module.web_app.container_app_id}/customDomainName/${each.value.name}"
}
