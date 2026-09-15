resource_group_name     = "kanban-dev-rg"
location                = "Poland Central"
env                     = "dev"
github_repository_owner = "dominiksmolinski3"
postgres_sku_name       = "B_Standard_B1ms"
postgres_storage_mb     = 32768
postgres_zone           = "1"

postgres_backup_retention_days        = 7
postgres_geo_redundant_backup_enabled = false

key_vault_purge_protection_enabled     = false
key_vault_purge_soft_delete_on_destroy = true

# Attachments are throwaway here; the paired-region copies are not worth paying for.
storage_replication_type = "LRS"

# The edge is free to scale and the API is not, which is the whole point of two variables. Both are
# left at 1 here: dev has one user, and phase 2 of the split - raising this one alone - is worth
# doing deliberately rather than as a side effect of the rename.
web_max_replicas = 1
api_max_replicas = 1
