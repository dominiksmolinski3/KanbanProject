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

# Phase 4 of the container-split plan. All five replica blockers are cleared: the outbox claim and
# the deadline sweep claim their own rows, AuthRateLimiter's escalation lives in Redis, the STOMP
# broker relay replaces the in-JVM simple broker, and the attachment transfer semaphore divides by
# a replica-count hint. Matches web_max_replicas rather than a new number picked without a reason -
# this deployment has no data yet suggesting the API needs a different ceiling than the edge does.
web_max_replicas = 5
api_max_replicas = 5

# The fleet's share of the database's connections, divided by api_max_replicas to reach each
# replica's Hikari pool (30 / 5 = 6). B_Standard_B1ms has max_connections = 50 and reserves 10 for
# superusers - both measured on psql-dev-g1tuv, not read off a table - so 40 are available and this
# leaves 10 of them: enough for a psql session and for the overlap while Container Apps runs a new
# revision beside the old one. Hikari's own default would have been 10 per replica, held idle, or
# 50 against a server with 40.
api_db_connection_budget = 30
