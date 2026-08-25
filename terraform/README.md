# Terraform Infrastructure for Kanban Project

This directory contains the Terraform configuration for deploying the Kanban project on Azure.

## Prerequisites

- [Terraform CLI](https://learn.hashicorp.com/tutorials/terraform/install-cli)
- [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli)
- Logged in to Azure CLI and correct subscription selected
- An Azure Storage Account to store Terraform state

## Setup

1) Azure login and subscription

```powershell
az login
# Optional: select subscription explicitly
az account set --subscription "<subscription-id-or-name>"
```

2) Create storage for Terraform state (once per organization)

```powershell
az group create --name tfstate-rg --location "West Europe"
az storage account create --name tfstatekanban --resource-group tfstate-rg --location "West Europe" --sku Standard_LRS
az storage container create --name tfstate --account-name tfstatekanban
```

2b) Grant yourself access to the Terraform state container

This project uses the `azurerm` backend with `use_azuread_auth = true`, so you need **Storage Blob data-plane** permissions on the state storage account.

Assign at least `Storage Blob Data Contributor` (recommended) on the storage account scope:

```powershell
$subId = az account show --query id -o tsv
$userObjectId = az ad signed-in-user show --query id -o tsv
$scope = "/subscriptions/$subId/resourceGroups/tfstate-rg/providers/Microsoft.Storage/storageAccounts/tfstatekanban"

az role assignment create --assignee-object-id $userObjectId --assignee-principal-type User --role "Storage Blob Data Contributor" --scope $scope
```

If you get an authorization error creating the role assignment, ask a subscription Owner/User Access Administrator to run it.

2c) Permission to create role assignments

Terraform creates two Azure RBAC role assignments on the Key Vault (see
[Key Vault authorization](#key-vault-authorization) below), so the identity running
`terraform apply` needs `Microsoft.Authorization/roleAssignments/write` -- i.e.
**Owner** or **User Access Administrator** on the subscription or on the target
resource group. `Contributor` alone is not enough and fails at apply time with
`AuthorizationFailed` on the role assignment.

```powershell
# Check whether you already have it
az role assignment list --assignee $(az ad signed-in-user show --query id -o tsv) --query "[].roleDefinitionName" -o tsv
```

3) Provide required secrets/variables

Values supplied through variables:
- `spring_mail_username`
- `spring_mail_password`
- `captcha_enabled`
- `captcha_secret`
- `vite_recaptcha_site_key`

`jwt_secret_key` is **not** an input -- Terraform generates it. See [Secrets](#secrets).

Optional (recommended):
- `app_image_tag` - container image tag deployed to Azure Container Apps. For reproducible deployments, set this to an immutable value like the git SHA pushed by the CI pipeline.

Optional (monitoring):
- `alert_email` - if set, Terraform creates an Azure Monitor action group and basic Container Apps metric alerts (CPU + memory).

Recommended: copy `dev.local.auto.tfvars.example` to `dev.local.auto.tfvars` and fill in values for local development (auto-loaded and usually gitignored). Alternatively, use the provided `*.tfvars` files and pass with `-var-file`.

Note: the `azurerm` provider uses the active Azure CLI context by default (`use_cli = true`). If Terraform cannot determine your subscription for any reason, set `subscription_id` in `dev.local.auto.tfvars` or export `ARM_SUBSCRIPTION_ID`.

## Deployment

1) Initialize Terraform for a target environment by setting a distinct backend key

Use a unique state key per environment instead of Terraform workspaces:

```powershell
# Development
terraform init -reconfigure -backend-config="key=env/dev/terraform.tfstate"

# Production
terraform init -reconfigure -backend-config="key=env/prod/terraform.tfstate"
```

Note: Re-run init with `-reconfigure` and a different `key` when switching environments.

2) Plan and apply

```powershell
# Development
terraform plan  -var-file "dev.tfvars"
terraform apply -var-file "dev.tfvars"

# Production
terraform plan  -var-file "prod.tfvars"
terraform apply -var-file "prod.tfvars"
```

Note: if you see `subscription ID could not be determined`, set `subscription_id` in `dev.local.auto.tfvars` (preferred) or export `ARM_SUBSCRIPTION_ID` in your shell.

## CI/CD
The existing GitHub Actions workflow in `.github/workflows/kanban-cd.yml` builds and pushes the Docker image to GitHub Container Registry (public). The Container App pulls the public image without authentication. The Container App's managed identity is granted the `Key Vault Secrets User` role to read application secrets from Key Vault -- see [Key Vault authorization](#key-vault-authorization) for the full access model.

## Notes

- **Password generation**: the Postgres module generates the administrator password internally, and the `container_app` module generates the JWT signing key the same way; both are stored in Key Vault and never passed in as variables. See [Secrets](#secrets).
- **Logging/analytics**: a Log Analytics Workspace is created and connected to the Container Apps Environment, so Container Apps logs/metrics are available in Azure Monitor Logs.

### Secrets

All application secrets live in Key Vault and are read by the Container App's managed
identity at revision start. They get into the vault two ways.

**Generated.** The Postgres admin password and the JWT signing key have no external
issuer, so Terraform generates them with `random_password` and nobody ever types or
sees them. The JWT key is stored base64-encoded, because `JwtService.getSignInKey`
runs `Decoders.BASE64.decode(...)` then `Keys.hmacShaKeyFor(...)` and throws on
anything under 32 decoded bytes.

**Supplied.** `spring_mail_password` and `captcha_secret` are issued by Gmail and
Google, so they cannot be generated. They come in as variables, which means they sit
in your tfvars file and in Terraform state in plaintext.

That is a deliberate acceptance, not an oversight. The identity running
`terraform apply` is granted `Key Vault Secrets Officer` on the vault (it has to be --
Terraform writes the Postgres secrets), so an applier can already read every secret in
the vault with one `az keyvault secret show`. Keeping these two out of tfvars would not
deny them anything they don't already hold. Both are also revocable from a console in
seconds with no downtime and no data loss, so the cost of exposure is a rotation.

What follows from that: **the state blob is as sensitive as the vault**, and the
control that actually matters is who can read it. Treat `Storage Blob Data Reader` on
the state container as equivalent to full production credential access, and grant it
accordingly -- see SEC-02 in the infrastructure review.

### Key Vault authorization

The vault is created with `enable_rbac_authorization = true`, so **Azure RBAC is the
only thing that grants data-plane access** -- Key Vault access policies are ignored
entirely. Two role assignments carry the whole model:

| Principal | Role | Why |
|---|---|---|
| The identity running `terraform apply` | `Key Vault Secrets Officer` | Terraform writes the Postgres and application secrets into the vault |
| The Container App's user-assigned identity | `Key Vault Secrets User` | The app resolves its `secret { key_vault_secret_id = ... }` references at revision start |

Two consequences worth knowing:

- **Control-plane roles do not imply data-plane access.** Being Owner or Contributor
  on the resource group lets you manage the vault but not read or write its secrets.
  To inspect secrets by hand (`az keyvault secret show`), assign yourself
  `Key Vault Secrets User` or `Key Vault Secrets Officer` at the vault scope.
- **Role assignments propagate asynchronously.** Each assignment is followed by a
  60-second `time_sleep` before anything touches a secret, because Key Vault's data
  plane can still answer `403` on a grant Azure has already accepted. If an apply
  fails with `Forbidden` on `azurerm_key_vault_secret` or the first Container App
  revision fails to start, re-running `terraform apply` is usually enough.

Migrating a vault created before this change: `terraform apply` flips it from
access-policy mode to RBAC in place. The pre-existing access policies stay recorded
on the vault but stop being consulted; they can be cleared afterwards with
`az keyvault delete-policy` if you want a clean resource.
