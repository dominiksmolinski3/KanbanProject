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

# The edge is free to scale and the API is not, which is the whole point of two variables. Phase 2
# of the split raises the edge ceiling alone: nginx serving its own image holds no state, so
# nothing has to move before this can. api_max_replicas stays at 1 - three of the five replica
# blockers in the container-split plan are still open (the STOMP broker and the rate limiter both
# hold state in the JVM), and raising it before they're fixed multiplies rate limits and drops
# board events for whichever replica isn't holding a given socket.
web_max_replicas = 5
api_max_replicas = 1
