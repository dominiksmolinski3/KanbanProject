resource_group_name     = "kanban-prod-rg"
location                = "West Europe"
env                     = "prod"
github_repository_owner = "dominiksmolinski3"

postgres_sku_name                  = "GP_Standard_D2ds_v4"
postgres_storage_mb                = 131072
postgres_zone                      = "1"
postgres_high_availability_mode    = "ZoneRedundant"
postgres_standby_availability_zone = "2"

postgres_backup_retention_days        = 35
postgres_geo_redundant_backup_enabled = true

key_vault_purge_protection_enabled     = true
key_vault_soft_delete_retention_days   = 90
key_vault_purge_soft_delete_on_destroy = false

extra_cors_origins = ["https://kanbanproject.pl", "https://www.kanbanproject.pl"]
