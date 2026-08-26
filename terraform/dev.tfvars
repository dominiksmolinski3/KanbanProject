resource_group_name     = "kanban-dev-rg"
location                = "Poland Central"
env                     = "dev"
github_repository_owner = "danielrudzinski"
postgres_sku_name   = "B_Standard_B1ms"
postgres_storage_mb = 32768
postgres_zone       = "1"

key_vault_purge_protection_enabled     = false
key_vault_purge_soft_delete_on_destroy = true
