variable "subscription_id" {
  description = "Subscription that holds the state account and the environments. Null means the active Azure CLI subscription."
  type        = string
  nullable    = true
  default     = null
}

variable "github_repository" {
  description = "owner/name of the repository whose workflows may log in as the drift identity."
  type        = string
  default     = "dominiksmolinski3/KanbanProject"
}

variable "github_oidc_subject_prefix" {
  description = "What GitHub puts before :environment: in the token's subject. This repository issues the immutable-id form, so it is sub_claim_prefix from `gh api repos/<owner>/<repo>/actions/oidc/customization/sub`, not repo:<owner>/<repo>."
  type        = string
  default     = "repo:dominiksmolinski3@115213364/KanbanProject@1349659343"
}

variable "drift_environment" {
  description = "GitHub environment the drift workflow runs in. The federated credential trusts this environment only."
  type        = string
  default     = "terraform-drift"
}

variable "environment_resource_groups" {
  description = "Resource groups the drift plan refreshes. The first holds the Key Vault; the others are the backup and mail groups."
  type        = list(string)
  default     = ["kanban-dev-rg", "kanban-dev-backup-rg", "rg-kanbanproject"]
}

variable "owner_tag" {
  type    = string
  default = "dominiksmolinski3"
}

variable "drift_refresh_actions" {
  description = "Actions beyond Reader. All but the first are what a refresh of dev was measured calling; the first lets the workflow add its runner to the vault firewall."
  type        = list(string)
  default = [
    "Microsoft.KeyVault/vaults/write",
    "Microsoft.App/containerApps/listSecrets/action",
    "Microsoft.App/jobs/listSecrets/action",
    "Microsoft.Communication/CommunicationServices/listKeys/action",
    "Microsoft.EventGrid/systemTopics/eventSubscriptions/getFullUrl/action",
    "Microsoft.OperationalInsights/workspaces/sharedKeys/action",
    "Microsoft.Storage/storageAccounts/listKeys/action",
  ]
}
