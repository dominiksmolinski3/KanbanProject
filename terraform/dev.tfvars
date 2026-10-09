resource_group_name     = "kanban-dev-rg"
location                = "Poland Central"
env                     = "dev"
github_repository_owner = "dominiksmolinski3"
owner_tag               = "dominiksmolinski3"
postgres_sku_name       = "B_Standard_B1ms"
postgres_storage_mb     = 32768
postgres_zone           = "1"

postgres_backup_retention_days        = 35
postgres_geo_redundant_backup_enabled = false
postgres_password_auth_enabled        = false

key_vault_purge_protection_enabled     = false
key_vault_purge_soft_delete_on_destroy = true

storage_replication_type = "LRS"

web_max_replicas = 5
api_max_replicas = 5

api_db_connection_budget = 30

monitoring_enabled      = true
api_otlp_export_enabled = true

# The drift workflow's identity, so monitoring-drift.yml can run the VM's configuration in check mode.
monitoring_operator_object_ids = ["1f493a12-84ca-4024-933c-d3130883f9cc"]

monthly_budget = 40

custom_domain = {
  name             = "kanbanproject.pl"
  certificate_name = "kanbanproject.pl-cae-dev-260921124711"
}

acs = {
  resource_group_name        = "rg-kanbanproject"
  communication_service_name = "acs-kanbanproject"
  email_service_name         = "kanban-csemail"
}

grafana_custom_domain = {
  name             = "grafana.kanbanproject.pl"
  certificate_name = "mc-cae-dev-grafana-kanbanpr-3776"
}
