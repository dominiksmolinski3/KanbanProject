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
az storage account create --name tfstatekanban --resource-group tfstate-rg --location "West Europe" --sku Standard_LRS --min-tls-version TLS1_2 --https-only true --allow-blob-public-access false --allow-shared-key-access false
az storage account blob-service-properties update --account-name tfstatekanban --resource-group tfstate-rg --enable-versioning true --enable-delete-retention true --delete-retention-days 30 --enable-container-delete-retention true --container-delete-retention-days 30
```

Run these against an existing account too -- `create` is the only command above that
fails if the account already exists; swap it for `az storage account update` with the
same flags.

This account holds the generated Postgres administrator password and JWT signing key in
plaintext, which is why it is worth hardening -- see [Secrets](#secrets).

Create the container **after** step 2b, not here: with shared key access disabled it has
to be created over Entra ID auth, which needs the role assignment to exist first.

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

Then create the state container. `--auth-mode login` is required because the account has
no shared key access; if this returns `403`, give the role assignment a minute to
propagate and retry.

```powershell
az storage container create --name tfstate --account-name tfstatekanban --auth-mode login
```

To check an existing account against the settings above:

```powershell
az storage account show -n tfstatekanban -g tfstate-rg --query "{tls:minimumTlsVersion,sharedKey:allowSharedKeyAccess,publicBlob:allowBlobPublicAccess,https:enableHttpsTrafficOnly}" -o json
```

`"sharedKey": null` means shared key access has never been set, which Azure treats as
**enabled** -- re-run the `update` above to disable it. (Use `-o json`, not `-o table`:
table output silently drops a column whose value is null.)

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

Optional (network) - see [Container App ingress restrictions](#container-app-ingress-restrictions):
- `allowed_ingress_cidrs` - CIDR ranges allowed to reach the app. Empty (the default) leaves ingress open to the internet.

Optional (monitoring):
- `alert_email` - if set, Terraform creates an Azure Monitor action group and basic Container Apps metric alerts (CPU + memory).

Optional (database sizing) - see [Postgres sizing and availability](#postgres-sizing-and-availability):
- `postgres_sku_name`, `postgres_storage_mb`, `postgres_zone`
- `postgres_high_availability_mode`, `postgres_standby_availability_zone`

Optional (Key Vault durability) - see [Key Vault durability](#key-vault-durability):
- `key_vault_purge_protection_enabled`, `key_vault_purge_soft_delete_on_destroy`
- `key_vault_soft_delete_retention_days`

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

### Network layout

The VNet is `10.0.0.0/16` and is carved into three subnets, each with one job.

| Subnet | Prefix | Holds |
|---|---|---|
| `snet-backend-<env>` | `10.0.0.0/23` | the Container Apps environment (`infrastructure_subnet_id`) |
| `snet-db-<env>` | `10.0.2.0/24` | Postgres Flexible Server, delegated to `Microsoft.DBforPostgreSQL/flexibleServers` |
| `snet-pe-<env>` | `10.0.3.0/28` | private endpoint NICs -- today just the Key Vault one |

**The backend subnet is dedicated to the Container Apps environment.** Azure treats an
infrastructure subnet as platform-managed and requires that nothing else live in it,
so the Key Vault private endpoint gets its own subnet rather than sharing that one.
Keeping the two apart also means `nsg-pe-${env}` (see
[Network security groups](#network-security-groups)) does not have to be written
around whatever the Container Apps platform needs.

The backend subnet keeps its `Microsoft.KeyVault` service endpoint, and that subnet --
not the private endpoint subnet -- is what the vault's `network_acls` allow. Those are
two different paths to the same vault: the service endpoint covers the app's own
egress if it ever resolves the vault's public name, while the private endpoint is what
the `privatelink.vaultcore.azure.net` zone actually resolves to for every client in
the VNet. Adding a private endpoint's own subnet to a resource's ACL does nothing --
private endpoint traffic bypasses the firewall entirely -- which is why the two
variables (`allowed_subnet_id`, `private_endpoint_subnet_id`) are separate.

Migrating an environment created before the split: `terraform apply` destroys and
recreates the private endpoint in the new subnet, so its private IP changes. The DNS
A record is managed by the endpoint's `private_dns_zone_group` and follows
automatically; expect the vault to be briefly unresolvable inside the VNet while the
replacement lands.

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
accordingly. The account's own hardening is applied in [Setup](#setup) step 2.

### Postgres sizing and availability

The database SKU, storage and availability zone are per-environment variables, set
in the `*.tfvars` files. The defaults are the dev values, so an environment that
says nothing gets the cheap shape.

| | dev / uat | prod |
|---|---|---|
| `postgres_sku_name` | `B_Standard_B1ms` | `GP_Standard_D2ds_v4` |
| `postgres_storage_mb` | 32768 (32 GiB) | 131072 (128 GiB) |
| `postgres_high_availability_mode` | null | `ZoneRedundant` |

Three things about that table are worth knowing before changing it.

**Burstable is a throttle, not just a small server.** A `B_` SKU runs at a fraction
of a vCore and spends credits to exceed it. Once the credits are gone the server is
held at its baseline for as long as the load lasts, which shows up as query latency
that gets worse the longer the pressure continues rather than levelling off.

**High availability is a property of the tier.** Azure does not offer it on Burstable
at all, so `postgres_high_availability_mode` and a `B_` SKU are a combination the API
rejects - and it rejects it at create time, after the vnet, the vault and the secrets
already exist. A `precondition` in the module fails the plan instead. The standby is a
second full server, so `ZoneRedundant` roughly doubles compute cost on top of the tier
change; setting the mode to `null` keeps a single-zone prod that still is not
throttled.

**Storage only grows.** Azure cannot shrink `storage_mb`, so raising it is a one-way
door. The size also sets the IOPS ceiling - 32 GiB is the smallest tier and caps out
correspondingly, which makes it a throughput decision as much as a capacity one.

Changing the SKU or storage on a live server is an in-place scale with a restart, so
expect a short outage. `postgres_zone` is different: Azure cannot move a running
server between zones, and after an HA failover the primary is in the standby's zone,
which the next `terraform plan` will try to undo. Treat the zone as set at creation.

### Container App ingress restrictions

**What this is for: keeping non-prod environments off the public internet. It is not
a defence for prod.**.

The Container App is internet-facing (`external_enabled = true`) and serves
`/auth/**` -- login, signup, verification-code resend -- without authentication, with
no rate limiting anywhere in the backend. `allowed_ingress_cidrs` renders one
`ip_security_restriction` block per entry:

```hcl
allowed_ingress_cidrs = ["203.0.113.42/32", "198.51.100.0/24"]
```
### Key Vault durability

The vault holds the only copy of two secrets nobody can reissue: the Postgres admin
password and the JWT signing key are both generated by `random_password` and never
written anywhere else. Losing the vault is losing them.

Soft delete is always on and cannot be disabled, but on its own it does not save you
here, because the azurerm provider's `key_vault.purge_soft_delete_on_destroy` feature
defaults to **true** -- a `terraform destroy` deletes the vault and then purges it in
the same run. The `features` block in `providers.tf` turns that off by default.

Two settings, both per-environment:

| | dev | uat / prod |
|---|---|---|
| `key_vault_purge_protection_enabled` | `false` | `true` |
| `key_vault_purge_soft_delete_on_destroy` | `true` | `false` |
| `key_vault_soft_delete_retention_days` | 90 | 90 |

dev is disposable on purpose. Vault names are deterministic (`kv-<env>-kanban`) and a
soft-deleted name stays reserved for the whole retention window, so protecting dev
would mean a teardown blocks its own rebuild for 90 days. uat and prod are the other
way round: a destroy there leaves a recoverable vault, and the next `terraform apply`
picks it back up, because the provider's `recover_soft_deleted_key_vaults` default
restores a soft-deleted vault rather than failing on the name.

Two one-way doors in that table:

- **Purge protection cannot be switched off.** Azure accepts an apply that enables it
  and rejects every apply that tries to disable it. Once uat or prod has been applied,
  removing the vault before its retention window expires is not possible through
  Terraform or the portal, and a support ticket will not shorten it either.
- **`soft_delete_retention_days` is immutable.** The provider marks it ForceNew, so
  changing the number plans a destroy and recreate of the vault -- which, on a
  purge-protected vault, is a plan that cannot apply. It is pinned to 90 (Azure's
  default and its maximum) everywhere so no environment ever plans that replacement.
### Network security groups

All three subnets carry an NSG (`nsg-backend-${env}`, `nsg-db-${env}`,
`nsg-pe-${env}`), defined in [modules/vnet/nsg.tf](modules/vnet/nsg.tf) alongside the
subnets whose CIDRs they reference.

The reason they exist is the last of Azure's built-in rules: `AllowVnetInBound` at
priority 65000 permits any address in the vnet to reach any port in any other subnet.
Without an NSG, a foothold anywhere in `snet-backend` reaches Postgres on 5432
directly. Each NSG therefore re-denies inbound `VirtualNetwork` traffic at priority
4096 and re-allows only the documented flows:

| | `snet-backend` (10.0.0.0/23) | `snet-db` (10.0.2.0/24) | `snet-pe` (10.0.3.0/28) |
|---|---|---|---|
| 100 | 80/443 from `ingress_source_address_prefixes` | 5432 from `snet-backend` | 443 from `snet-backend` |
| 110 | anything within the subnet | anything within the subnet | -- |
| 120 | 30000-32767 from `AzureLoadBalancer` | -- | -- |
| 4096 | deny inbound from `VirtualNetwork` | deny inbound from `VirtualNetwork` | deny inbound from `VirtualNetwork` |

The deny is sourced on `VirtualNetwork` rather than `*` so the platform's
`AllowAzureLoadBalancerInBound` at 65001 still applies. Rule 110 is not optional in
either subnet: the Container Apps consumption environment's own components talk
across the infrastructure subnet on unpublished ports, and flexible server replicates
to its standby inside the delegated subnet. `snet-pe` needs no such rule -- it holds
private endpoint NICs and nothing that talks to a neighbour.

**`snet-pe` only gets an NSG because its network policies are switched on for it.**
Azure does not apply NSGs to private endpoint NICs by default, which is why
`private_endpoint_network_policies` on that subnet is `NetworkSecurityGroupEnabled`
rather than the `Disabled` that private endpoints ship with. Set it back to
`Disabled` and `nsg-pe-${env}` stays associated and stops being enforced, silently.
The one allow rule is 443 from `snet-backend`, because reaching the vault over the
private endpoint is the only reason anything in the vnet talks to that subnet; a
second private endpoint landing there later needs its own rule and its own port.

`ingress_source_address_prefixes` defaults to `["Internet"]`, which is what the
container app's `external_enabled = true` needs. Narrow it in a `*.tfvars` file for
an environment that should not be publicly reachable -- note that this only filters
at the subnet edge, so it is a blunt instrument rather than a substitute for a WAF.

**Outbound is deliberately left on the Azure defaults.** The app's egress does not
reduce to service tags: it pulls its image from `ghcr.io`, sends mail over SMTP to an
external provider and verifies captchas against `google.com`, on top of what the
consumption environment itself needs (MCR, Entra ID, Azure Monitor, Azure Files on
445, NTP on UDP 123). A default-deny egress rule that misses one of those surfaces as
a revision that never becomes healthy rather than as a clear error, so egress
filtering belongs behind a NAT gateway or firewall, not here.

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
