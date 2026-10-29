# Terraform for KanbanProject

This directory deploys the whole thing to Azure. It's also where I wrote down why the
infrastructure looks the way it does, because most of the odd-looking settings here are odd for a
reason, and the reason is usually something that broke.

Right now **dev is the only environment**, and it is torn down to save credits. The state
container and everything needed to rebuild it with `./tf.sh dev apply` are still there.

## Prerequisites

- [Terraform CLI](https://developer.hashicorp.com/terraform/install)
- [Azure CLI](https://learn.microsoft.com/cli/azure/install-azure-cli), logged in, with the right
  subscription selected
- An Azure Storage account for Terraform state (step 2 below creates one)
- `bash` and `python3` on `PATH` for `tf.sh` and the scripts it calls

## Setup

### 1. Log in

```powershell
az login
az account set --subscription "<subscription-id-or-name>"   # if you have more than one
```

### 2. Create the state storage (once per subscription)

```powershell
az group create --name tfstate-rg --location "West Europe"
az storage account create --name tfstatekanban --resource-group tfstate-rg --location "West Europe" --sku Standard_LRS --min-tls-version TLS1_2 --https-only true --allow-blob-public-access false --allow-shared-key-access false
az storage account blob-service-properties update --account-name tfstatekanban --resource-group tfstate-rg --enable-versioning true --enable-delete-retention true --delete-retention-days 30 --enable-container-delete-retention true --container-delete-retention-days 30
```

If the account already exists, run the same thing with `az storage account update` in place of
`create`; it's the only command above that fails on an existing account. Once the account exists,
`terraform/bootstrap` manages it, so you only need these commands on a subscription that has none
yet (see [Drift detection](#drift-detection)).

I harden this account more than you might expect. The generated secrets never reach it, but the
ACS connection string and `captcha_secret` do, in plaintext. [Secrets](#secrets) explains why I
accept that.

Don't create the container yet. With shared key access off it has to be created with Entra auth,
and that needs the role assignment from step 2b first.

### 2b. Give yourself access to the state container

The `azurerm` backend runs with `use_azuread_auth = true`, so you need a data-plane role on the
state account. `Storage Blob Data Contributor` is what I use:

```powershell
$subId = az account show --query id -o tsv
$userObjectId = az ad signed-in-user show --query id -o tsv
$scope = "/subscriptions/$subId/resourceGroups/tfstate-rg/providers/Microsoft.Storage/storageAccounts/tfstatekanban"

az role assignment create --assignee-object-id $userObjectId --assignee-principal-type User --role "Storage Blob Data Contributor" --scope $scope
```

If that fails with an authorization error, someone with Owner or User Access Administrator on the
subscription has to run it for you.

Then create the container. `--auth-mode login` is required because the account has no shared keys.
A `403` here usually means the role assignment hasn't propagated yet; wait a minute and retry.

```powershell
az storage container create --name tfstate --account-name tfstatekanban --auth-mode login
```

To check an existing account against these settings:

```powershell
az storage account show -n tfstatekanban -g tfstate-rg --query "{tls:minimumTlsVersion,sharedKey:allowSharedKeyAccess,publicBlob:allowBlobPublicAccess,https:enableHttpsTrafficOnly}" -o json
```

`"sharedKey": null` means nobody ever set it, and Azure treats that as **enabled**. Re-run the
`update` to turn it off. Use `-o json` here: `-o table` silently drops columns whose value is null.

### 2c. Permission to create role assignments

Terraform creates role assignments on the Key Vault and on the attachment storage account, so
whoever runs `terraform apply` needs `Microsoft.Authorization/roleAssignments/write`. In practice
that means **Owner** or **User Access Administrator** on the subscription or the resource group.
`Contributor` isn't enough; it fails at apply time with `AuthorizationFailed`.

```powershell
az role assignment list --assignee $(az ad signed-in-user show --query id -o tsv) --query "[].roleDefinitionName" -o tsv
```

### 3. Variables and secrets

You supply these:

- `acs`: the resource group, Communication Services resource and email service that carry mail
  (see [Mail](#mail)). `null`, the default, turns mail off.
- `captcha_enabled` and `captcha_secret`.

`jwt_secret_key` is **not** an input. Terraform generates it (see [Secrets](#secrets)).

- `app_image_tag` is **required and has no default**. It has to be an immutable value, which in
  practice means the git SHA CD pushed. A mutable tag like `latest` breaks deploys, not just
  reproducibility. The tag is the only part of the container template that changes between
  releases. If it never changes, every apply renders the same template, Terraform sees no diff, and
  no new revision rolls out while every plan says it is up to date.

  So `tf.sh apply` refuses a tag that isn't a commit on `origin/main`, and refuses one that's behind
  the tip. The check runs before `terraform init` and compares against your local `origin/main`
  without fetching.

  `./tf.sh <env> apply --allow-stale-image` overrides it. There are two legitimate reasons to use
  it: CD takes a few minutes to push the tip's image, so right after a merge you want the previous
  commit; and a rollback should look deliberate. Applying a **saved plan** skips the check (and says
  so), since the plan already carries its own values.
- `key_vault_allowed_ips`: public addresses allowed through the Key Vault firewall. You need this
  whenever you apply from outside the VNet, which includes every workstation (see
  [Key Vault network access](#key-vault-network-access)).
- `allowed_ingress_cidrs`: CIDR ranges allowed to reach the app. Empty, the default, leaves it open
  to the internet (see [Ingress restrictions](#ingress-restrictions)).
- `alert_email`: if set, Terraform creates an action group and the alerts in
  `modules/diagnostics`.
- Database sizing: `postgres_sku_name`, `postgres_storage_mb`, `postgres_zone`,
  `postgres_high_availability_mode`, `postgres_standby_availability_zone` (see
  [Postgres sizing and availability](#postgres-sizing-and-availability)).
- Key Vault durability: `key_vault_purge_protection_enabled`,
  `key_vault_purge_soft_delete_on_destroy`, `key_vault_soft_delete_retention_days` (see
  [Key Vault durability](#key-vault-durability)).

Put anything secret or personal in `dev.local.tfvars` (copy `dev.local.tfvars.example`). It's
gitignored, and `./tf.sh dev ...` passes it after `dev.tfvars` so it wins. The exact file name
matters; see [Local overrides](#local-overrides-one-per-environment).

The provider uses your Azure CLI login (`use_cli = true`). If Terraform says `subscription ID could
not be determined`, set `subscription_id` in `dev.local.tfvars` or export `ARM_SUBSCRIPTION_ID`.

## Deployment

Always go through `tf.sh`. It takes the environment name once and derives both the state key and the
var-file from it, and nothing else keeps those two paired:

```bash
./tf.sh dev plan -out=dev.tfplan
./tf.sh dev apply dev.tfplan
```

It runs `init -reconfigure` every time, so switching environments can't leave the previous
backend configured.

`uat.tfvars` and `prod.tfvars` are templates. I keep them so their configuration doesn't rot:
`./tf.sh uat plan -var app_image_tag=<sha>` renders a plan and creates nothing, and `tf.sh` refuses
`apply`, `destroy` and `import` for both. Their regions are ones the subscription's policy allows
(`polandcentral`, `norwayeast`, `francecentral`, `swedencentral`, `uksouth`). Prod would sit in
Sweden Central because geo-redundant backup needs a paired region, and Poland Central has none.

After a successful `apply`, `tf.sh` also runs the same check that
`.github/workflows/deployed-contract.yml` runs daily, against the new `container_app_url`. It asks
the running site whether it serves what `main` says it should, which catches a revision that never
rolled out. Without `python3` or that output it warns and skips
instead of failing an apply that already succeeded, but a deployment that answers wrong does fail
the command.

<details>
<summary>Doing it by hand</summary>

There are no Terraform workspaces. Environments are separated only by state key, which is passed on
the command line rather than written into `providers.tf`:

```powershell
terraform init -reconfigure -backend-config="key=env/dev/terraform.tfstate"
terraform plan  -var-file "dev.tfvars"
terraform apply -var-file "dev.tfvars"
```

I'd avoid it: it's easy to pair one environment's key with another's var-file.

</details>

## CI/CD

`.github/workflows/kanban-cd.yml` builds the images and pushes them to the GitHub Container
Registry. The apps pull them anonymously, since the packages are public. Each app reads its
secrets from Key Vault through its own managed identity (see
[Key Vault authorization](#key-vault-authorization)).

`.github/workflows/terraform-ci.yml` runs on every PR that touches `terraform/`, `ansible/`,
`.checkov/`, the workflows or one of the Dockerfiles. It runs `fmt -check`, then `init -backend=false`
and `validate` for the root and for `terraform/bootstrap`, then TFLint with the azurerm ruleset, and
a blocking Checkov scan whose scope is `.checkov.yaml` (Terraform, the Dockerfiles and the
workflows). A gate job fails unless every job passed or had nothing to check. There's no automated
apply. Deploying stays a deliberate manual step (`./tf.sh <env> apply`).

### Why there's no plan on pull requests

A CI `plan` job would be useless, because it can't see the local var-file:

```
# what the CI job would have run
terraform plan -refresh=false -var-file=dev.tfvars
Plan: 0 to add, 2 to change, 15 to destroy.

# what I run locally, with the local var-file
./tf.sh dev plan
No changes. Your infrastructure matches the configuration.
```

The difference is the gitignored `dev.local.tfvars`. Every secret and environment-specific value
lives there on purpose, so one environment's values can't leak into another's state. CI doesn't have
that file, and several of those values decide whether resources exist at all: an empty
`mail_delivery_report_key` means no Event Grid resources, an empty `alert_email` means no alerts, an
empty `captcha_secret` means no Key Vault secret. CI would plan a different configuration and post
"15 to destroy" on every Terraform PR. A diff that's always alarming just trains reviewers to skim
past it.

The only fix would be to put dev's captcha secret and delivery-report key into GitHub secrets and
give CI a login that can read the ACS keys, which means a mail-sending credential in CI just to get
a review aid. That isn't worth it, so there is no plan job.

What that gives up is seeing a destroy-and-replace surprise before merge. What covers it instead:
`prevent_destroy` on Postgres and the other data-holding resources, and running `./tf.sh dev plan`
before merging anything structural.

### Drift detection

`.github/workflows/terraform-drift.yml` plans dev from `main` every night and opens a `sweep-red`
issue through `sweep-alarm.yml` if the plan isn't empty. That catches a portal change, an apply
from a branch that never merged, or a merge nobody applied.

It solves the missing-local-file problem differently: instead of keeping copies in GitHub secrets,
it reads the values back from where Terraform already put them. `scripts/drift-inputs.sh` rebuilds
`dev.local.tfvars` as JSON: the two secrets from Key Vault, `app_image_tag` and `captcha_enabled`
from the running API, the alert address from the action group, and the vault's IP rules. Run
locally, its output gives `No changes`. The catch is that **drift in those six values is invisible**,
because they're read from the thing being compared.

The run logs in as `id-kanban-terraform-drift`, a user-assigned identity with a federated
credential for the `terraform-drift` GitHub environment, so there's no client secret. This repo
issues OIDC subjects in GitHub's immutable-id form
(`repo:<owner>@<id>/<repo>@<id>:environment:...`), and the credential has to match that, not
`repo:<owner>/<repo>`. A mismatch fails the login with `AADSTS700213`.

It holds Reader on the subscription, Storage Blob Data Reader on the state container, Key Vault
Secrets User on `kanban-dev-rg`, and one custom role on the three resource groups. I built that role
from what a refresh actually called beyond Reader (from `TF_LOG=DEBUG` on a real plan), plus
`vaults/write` so the run can add its own address to the vault firewall and remove it afterwards:

- `listSecrets` on the container apps and jobs, `listKeys` on ACS and the storage accounts,
  `sharedKeys` on the workspace, and `getFullUrl` on the Event Grid subscription. The storage keys
  are harmless, since every account has shared keys off. The ACS keys and the vault's secrets
  aren't, which is why the credential trusts only one GitHub environment, and that environment only
  deploys from `main`.
- The run plans with `-lock=false`, so it never writes the state blob.
- `terraform_operator_object_id` keeps the vault's Secrets Officer assignment on the person who
  applies, since the planner is someone else. `key_vault_runner_ips` adds the runner to the IP
  rules during the plan, so that rule doesn't show up as drift.

The identity, its roles and the state account live in `terraform/bootstrap`, a separate root with
its own state (`bootstrap/terraform.tfstate` in the same container). A subscription Owner applies it
by hand, once:

```bash
cd terraform/bootstrap
terraform init
terraform apply
```

Then give the `terraform-drift` environment four variables from its outputs: `AZURE_CLIENT_ID`
(`drift_client_id`), `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` and `TERRAFORM_OPERATOR_OBJECT_ID`.
A missing one fails the run rather than skipping it.

`tfstate-rg` is read by name, not imported. It's in West Europe, which the location policy now
denies, so even a tag update on it would be refused. The account inside it is in Poland Central and
is managed normally, with a `CanNotDelete` lock.

## Notes

A Log Analytics workspace is connected to the Container Apps environment, so every app's logs and
platform metrics end up in Azure Monitor Logs.

### Network layout

<p align="center">
  <img src="../docs/architecture/azure-infrastructure.svg" alt="Azure network topology: a VNet with six subnets holding the Container Apps environment and its jobs, Postgres, the Key Vault, Blob Storage and Redis private endpoints and the monitoring VM, plus the backup region" width="100%"/>
</p>

The VNet is `10.0.0.0/16`, split into six subnets that each do one thing:

| Subnet | Prefix | Holds |
|---|---|---|
| `snet-backend-<env>` | `10.0.0.0/23` | the Container Apps environment (`infrastructure_subnet_id`) |
| `snet-db-<env>` | `10.0.2.0/24` | Postgres Flexible Server, delegated to `Microsoft.DBforPostgreSQL/flexibleServers` |
| `snet-pe-<env>` | `10.0.3.0/28` | the Key Vault private endpoint |
| `snet-storage-<env>` | `10.0.3.16/28` | the attachment blob private endpoint |
| `snet-redis-<env>` | `10.0.3.32/28` | the Managed Redis private endpoint |
| `snet-monitoring-<env>` | `10.0.3.48/28` | the Prometheus VM (see [Monitoring VM](#monitoring-vm)) |

The backend subnet belongs to Container Apps alone. Azure treats an infrastructure subnet as
platform-managed and doesn't want anything else in it, and keeping the endpoints out also means
their NSGs don't have to work around whatever the platform needs.

Each private endpoint gets its own subnet instead of sharing `snet-pe`. One subnet means one NSG,
and a shared rule would say "anything in the backend subnet may reach 443 anywhere in here". Then
the storage endpoint would be reachable because of a rule named `AllowKeyVaultFromBackend`. A /28
each is cheap, and every service's access gets its own rule.

`snet-db` keeps its `Microsoft.Storage` service endpoint even though nothing here seems to use it.
Azure adds it when the first flexible server lands in the subnet, the server ships its WAL through
it, and Microsoft's docs warn that removing it can break the server.

The backend subnet has no `Microsoft.KeyVault` service endpoint, and the vault's `network_acls` name
no subnet. Everything in the VNet resolves the vault through `privatelink.vaultcore.azure.net` to
the private endpoint, and private endpoint traffic isn't subject to the vault firewall anyway. The
Container Apps platform resolves Key Vault references as a trusted service
(`key_vault_allow_azure_services_bypass`). That and `key_vault_allowed_ips` are the only other ways
in.

### Secrets

Every application secret lives in Key Vault, and each app reads it through its managed identity
when a revision starts. Secrets get there in three ways.

**Generated.** The Postgres admin password, the JWT signing key and the RabbitMQ password have no
outside issuer, so Terraform generates them with an ephemeral `random_password` and writes them
through write-only attributes. Nobody types or sees them, and they're never in Terraform state. Each
is rewritten only when its version variable (`jwt_secret_version`, `password_version`) is bumped,
which is also how you rotate one; restart whatever reads it afterwards. The JWT key is stored
base64-encoded, because `JwtService.getSignInKey` decodes it and throws on anything under 32 bytes.

**Read from Azure.** The ACS connection string is the Communication Services resource's own key,
which Terraform reads from the resource it manages (see [Mail](#mail)). It ends up in state, not in
any tfvars file.

**Supplied.** `captcha_secret` comes from Google, so it can't be generated. It's a variable, which
means it sits in your tfvars file and in state in plaintext.

I accept that on purpose. Whoever runs `terraform apply` has to hold `Key Vault Secrets Officer`
on the vault, because Terraform writes the secrets, so they can already read every secret with one
`az keyvault secret show`. Keeping these two out of tfvars wouldn't hide anything from them. Both
can also be revoked from a console in seconds, so exposure costs a rotation.

That does mean **the state blob is as sensitive as the vault**. What matters is who can read it:
treat `Storage Blob Data Reader` on the state container like full credential access.

### Local overrides, one per environment

Anything that must not be committed (the ACS connection string, the captcha secret, the alert
address, your own IP for the Key Vault firewall) goes in `<env>.local.tfvars`, which is gitignored.
`./tf.sh <env> ...` passes it after the committed `<env>.tfvars`, and a file for another environment
simply isn't read.

The name must not contain `.auto.`. Terraform loads every `*.auto.tfvars` in the directory on every
run, whatever `-var-file` you pass, and `prod.tfvars` doesn't set the secrets because they have no
committed value. A `<env>.local.auto.tfvars` would carry dev's ACS connection string, captcha secret,
alert address and your IP straight into a prod plan. `tf.sh` refuses to run while any
`*.local.auto.tfvars` exists:

```
$ ./tf.sh dev plan
error: Terraform auto-loads these on every run, whatever environment you asked for:
  ./dev.local.auto.tfvars
rename each to <env>.local.tfvars (drop the '.auto.'); this script passes the one that matches.
```

### Mail

`modules/mail` owns the Communication Services resource, the email service, its Azure-managed
`*.azurecomm.net` domain (no DNS needed) and the link between them, all in the existing resource
group that `acs` names. Terraform reads the connection string and the `DoNotReply@` sender address
from them, writes the `ACS-EMAIL-CONNECTION-STRING` secret, and passes `ACS_EMAIL_CONNECTION_STRING`
and `ACS_EMAIL_SENDER_ADDRESS` to the API. Nothing about mail is pasted into tfvars.

The Communication Services resource, the email service and the domain have `prevent_destroy`. The
domain is a generated GUID subdomain, so a new one would change every sender
address. The diagnostic setting, the bounce alert and the Event Grid subscription for delivery
reports hang off the same resource, so they follow `acs` too.

With `acs` unset, the app starts and silently drops every message. That's what lets CI and a fresh
clone run without an Azure account. In production the same default would be a disaster: signup
answers `200`, saves an unverified account and a verification code, and sends nothing. The user
can't log in, and the revision looks healthy with nothing in the log.

So **a `prod` plan refuses to run while `acs` is `null`**:

```
prod would deploy with mail switched off.

Set acs for env = "prod".
```

`dev` and `uat` are expected to run with mail off. There's deliberately no override: a deployment
that can't send a verification code is one nobody can sign up to.

### Postgres sizing and availability

The SKU, storage and zone are per-environment variables in the `*.tfvars` files. The defaults are
dev's, so an environment that sets nothing gets the cheap shape. The uat and prod columns are what
the templates would build.

| | dev | uat | prod |
|---|---|---|---|
| `postgres_sku_name` | `B_Standard_B1ms` | `B_Standard_B1ms` | `GP_Standard_D2ds_v4` |
| `postgres_storage_mb` | 32768 (32 GiB) | 32768 (32 GiB) | 131072 (128 GiB) |
| `postgres_high_availability_mode` | null | null | `ZoneRedundant` |
| `postgres_backup_retention_days` | 35 | 14 | 35 |
| `postgres_geo_redundant_backup_enabled` | false | false | true |

Things worth knowing before you change any of it:

- **Burstable is a throttle, not just a small server.** A `B_` SKU runs at a fraction of a vCore
  and spends credits to go faster. When the credits run out, it's held at baseline for as long as
  the load lasts, so latency keeps getting worse instead of levelling off. There's an alert on
  remaining credits for this.
- **Connections are tight on B1ms.** It allows 50, 10 are reserved, so 40 are usable.
  `api_db_connection_budget` is split across `api_max_replicas`, and a `check` block plus
  `DatabasePoolBudgetTest` keep the budget inside what the SKU allows.
- **HA isn't available on Burstable.** Azure rejects that combination, but only at create time,
  after the VNet, vault and secrets already exist. A `precondition` in the module fails the plan
  instead. The standby is a second full server, so `ZoneRedundant` roughly doubles compute cost on
  top of the tier change.
- **Storage only grows.** Azure can't shrink `storage_mb`. The size also sets the IOPS ceiling, so
  it's a throughput decision as well as a capacity one.
- **HA isn't a backup.** A standby faithfully replicates the statement that dropped the table.
  Point-in-time restore is what saves you from a bad migration, and
  `postgres_backup_retention_days` is how far back it goes. Geo-redundant backup is fixed at
  create time (turning it on later replaces the server), and Poland Central has no pair, so dev
  can't have it.
- **The server refuses to be destroyed.** It has `prevent_destroy = true`, because several
  attributes (`zone` and `delegated_subnet_id` among them) force a replacement, and replacing this
  resource means losing the data. Without that, a tfvars edit could plan a destroy that looks like an
  ordinary diff. Tearing an environment down on purpose means deleting those lines first. That
  friction is intentional.

Changing the SKU or storage on a live server is an in-place scale with a restart, so expect a short
outage. The zone is different: Azure can't move a running server between zones, and after an HA
failover the primary is in the standby's zone, which the next plan will try to undo. Treat the zone
as fixed once the server exists.

### Database logins

The server accepts Entra tokens, plus the `psqladmin` password while
`postgres_password_auth_enabled` is true. The password is ephemeral and write-only, so it's never in
state. It's in Key Vault as `POSTGRES-PASSWORD` for the roles job only, and only while password login
is on. Turning that off deletes the secret and sets the server to a password nobody has.

| Login | Who | Can |
|---|---|---|
| `kanban-db-admin-identity-<env>` | the server's Entra admin; only the roles job uses it | create roles and grant the three below |
| `kanban-migrator-identity-<env>` | `kanban-api-migrate-<env>` | everything `kanban_owner` can, which owns the schema |
| `kanban-app-identity-<env>` | the API | `SELECT`/`INSERT`/`UPDATE`/`DELETE`, through `kanban_writer` |
| `kanban-backup-identity-<env>` | the nightly dump | `SELECT`, through `kanban_reader` |
| `psqladmin` | break-glass while password login is on | member of `kanban_owner` |

Each one fetches a token per new connection through `EntraTokenAuthenticationPlugin`, which the JDBC
URL names. The API runs with `DB_MIGRATE_ON_STARTUP=false` and can't alter the schema. Migrations
run in `kanban-api-migrate-<env>`, which starts the API image with `--migrate-only` and
`SET ROLE kanban_owner` (`DB_MIGRATION_INIT_SQL`), so new tables belong to `kanban_owner`. Default
privileges give `kanban_writer` and `kanban_reader` their rights on anything `kanban_owner`
creates.

`terraform apply` starts two manual jobs and waits for each (`scripts/run-container-app-job.sh`,
which needs `bash` and a logged-in `az`):

1. `kanban-<env>-db-roles` creates the Entra roles and the three group roles and grants the
   memberships. It reruns when its script or an identity changes. While password login is on, it
   also hands every table `psqladmin` created over to `kanban_owner`. With password login off it
   checks that nothing is left to hand over, and fails if something is.
2. `kanban-api-migrate-<env>` runs Flyway. It reruns whenever `app_image_tag` moves.

The API's revision depends on both, so a failed job stops the apply before the API changes.

To turn password login off: apply once with it on (the hand-over needs `psqladmin`), then set
`postgres_password_auth_enabled = false` and apply again. Break-glass is then a job running as the
Entra admin. Turning it back on writes a fresh password to the server and to Key Vault in the same
apply.

### Database dumps

Point-in-time restore lives inside the server's blast radius. Deleting the server, its resource
group or the subscription takes the backups with it, and you can't export them. `modules/backup`
keeps a copy that survives everything except losing the subscription.

- A Container Apps job, `kanban-<env>-pg-dump`, runs nightly at 02:15 UTC. An init container runs
  `pg_dump --format=custom` as the job's own identity (`kanban_reader`), and the main container
  uploads it with the same identity.
- The dumps go to a GRS account in **its own resource group in `backup_location`**
  (`swedencentral`), a region the policy allows and not the database's region.
- The job's identity has a custom role that can **only add blobs**: no read, no delete, no
  overwrite. The container has a 14-day immutability policy, so even a compromised job can't remove
  a recent dump. The policy is **locked**: not even an Owner can shorten or remove it, and the
  account can't be deleted while it holds a dump younger than 14 days, so plan for that when tearing
  an environment down.
- Lifecycle rules keep `daily/` for 14 days, `weekly/` (Sundays) for 8 weeks and `monthly/` (the 1st)
  for a year, moving monthly dumps to Cool after 30 days.
- The account has no private endpoint. `snet-backend` carries a `Microsoft.Storage.Global` service
  endpoint, which lets a subnet in one region be named in the network rules of an account in
  another, and the account denies everything else.
- `kanban-<env>-backup-stale` fires when no `BACKUP_OK` line has appeared in the job's logs for 26
  hours.
- A second job, `kanban-<env>-restore-drill`, runs at 03:15 UTC. It downloads the newest `daily/`
  dump with its own read-only identity, restores it into a throwaway Postgres inside the job, and
  prints `RESTORE_OK` with the migration and row counts. `kanban-<env>-restore-drill-failed` fires
  when that line hasn't appeared for 26 hours. A dump that doesn't restore isn't a backup.
- There's no automated point-in-time restore drill. Azure authorizes a restore as
  `flexibleServers/write` on the source server, with nothing narrower, so any identity that could
  run one could also reconfigure the production server. I run PITR tests by hand with my own
  account.

To run a dump now: `az containerapp job start -g kanban-dev-rg -n kanban-dev-pg-dump`. To restore,
download a dump and run `pg_restore --no-owner --dbname=<target>`. It restores into the local compose
Postgres as well as into a new server. Dumps don't include attachments; those live in blob storage.

### The web and API apps

Four Container Apps share one Managed Environment: `kanban-web-<env>`, `kanban-api-<env>`,
`kanban-broker-<env>` (see [RabbitMQ broker](#rabbitmq-broker)) and `kanban-grafana-<env>` (see
[Grafana](#grafana)). The web app is the only one the public app is reachable through:

| App | Module | Ingress | What it is |
|---|---|---|---|
| `kanban-web-<env>` | `modules/web_app` | **external** | nginx: the Vite bundle, plus a reverse proxy for `/api`, `/ws` and `/v3/api-docs` |
| `kanban-api-<env>` | `modules/api_app` | **internal** | the Spring Boot jar, reachable only from inside the environment |

What follows from that split:

- **The public origin is the web app's FQDN.** `terraform output container_app_url` names it. The
  API has no public URL; `api_internal_fqdn` is internal and nothing outside the environment
  resolves it.
- **nginx talks TLS to the API.** It isn't optional. An internal ingress still terminates TLS and
  answers plain HTTP with a `301` to the https URL, and nginx would hand that redirect back to the
  browser, pointing it at a name only the environment can resolve. `allow_insecure_connections = true`
  on the API isn't needed: the internal certificate carries `*.internal.<environment-domain>`, is issued by Microsoft's public CA, and
  verifies against the stock CA bundle. So the hop is authenticated as well as encrypted.
- **`ingress_trusted_proxy_count` is 3.** Three hops append to `X-Forwarded-For`: the web ingress
  appends the client, nginx appends that ingress's envoy, and the API's internal ingress appends
  nginx. Count the entries that get appended, not the proxies: with too small a count every request
  is keyed on the web ingress's envoy address, so the rate limiter holds one bucket for the whole
  internet and *Signed-in devices* shows the same IP for everyone.
- **`SECURITY_CORS_ALLOWED_ORIGINS` names the web app**, not the API. nginx forwards the browser's
  `Origin` unchanged, so Spring sees the address the user typed. Get it wrong and every call from the
  board fails with a 403 `Invalid CORS request` while the page itself loads fine.
- **One `app_image_tag` feeds both apps.** The bundle and the API it calls have to agree. One tag
  across both images guarantees that. Please don't split the variable for convenience; that's what
  makes version skew possible.

The API's name is built twice, once in the API module and once inside the edge's upstream in the web
module, so neither module depends on the other's resources. A `check` block in the root module
makes sure they still agree, because otherwise the failure is a green apply followed by a 502 on
every API call.

### The container images

The apps pull `ghcr.io/<github_repository_owner>/kanbanproject-app:<app_image_tag>` and
`ghcr.io/<github_repository_owner>/kanbanproject-web:<app_image_tag>`.

`github_repository_owner` has to be the account `kanban-cd.yml` publishes to. The workflow pushes
under the repository owner, so on a fork that's the fork's owner. A wrong value plans fine and then
fails at pull time.

GHCR authenticates with a user name and a token. Unlike ACR it doesn't accept an Azure managed
identity, so the apps can't use theirs. Two setups work:

- **Public package**: leave `ghcr_username` and `ghcr_token` empty. No registry is declared and the
  pull is anonymous. That's the default, and only makes sense while the package really is public.
- **Private package**: set both. The token (a PAT with `read:packages`) goes into Key Vault as
  `GHCR-TOKEN` and each app reads it through its identity, so it never appears in a template in
  clear text. Setting `ghcr_token` without `ghcr_username` fails the plan.

  The two apps read it through **different identities**, and the web app's grant covers the
  `GHCR-TOKEN` secret only, not the whole vault. Otherwise the nginx container could read the
  Postgres password and the JWT key, which it has no business doing.

A private package with neither set fails with `ImagePullBackOff`.

### Key Vault network access

The vault denies public traffic (`key_vault_network_default_action = "Deny"`). It admits the
private endpoint, trusted Azure services (Key Vault references) and whatever is in
`key_vault_allowed_ips`. Nothing discovers your address for you: a lookup on every plan would turn
the allow-list into "whoever ran Terraform last" and could never describe a CI runner with changing
addresses.

This matters because Terraform writes secrets over the vault's **data plane**. An apply from outside
the VNet without a matching entry fails on `azurerm_key_vault_secret` with `Forbidden` / `Client
address is not authorized`. That looks like the RBAC propagation delay described below, but it isn't,
and re-running won't help.

Pick one path per environment:

| Where `terraform apply` runs | What to configure |
|---|---|
| A self-hosted runner inside the VNet | Nothing. Leave `key_vault_allowed_ips` empty. |
| A runner or office with a stable egress IP | Commit that address or range to the environment's `*.tfvars`. |
| A developer workstation | Set `key_vault_allowed_ips` in the gitignored `dev.local.tfvars`. Find your address with `(Invoke-RestMethod https://api.ipify.org)`, and update it when your ISP changes it. |

Only commit addresses that don't move. A workstation address belongs in the local override so it
never ends up in the repo or in another environment's plan.

### Custom domain

`custom_domain` binds a hostname to the web app with an Azure-managed certificate validated over
HTTP. Its `https://` origin is added to the API's CORS list
automatically, so it doesn't also need to be in `extra_cors_origins`. DNS stays at the registrar:
the apex `A` record points at the environment's static IP, and the `asuid` TXT record carries the
verification id.

Creating one from scratch takes two applies, because Azure validates the certificate against a
hostname that's already on the app: first add the domain with binding `Disabled`, then create the
certificate.

### Ingress restrictions

This is for keeping non-prod environments off the public internet. **It is not a defence for
prod.**

The restriction sits on the web app, the only app with a public ingress. The API has nothing to
restrict, and that's the stronger control: `allowed_ingress_cidrs` is empty everywhere today, while
`external_enabled = false` on the API always holds.

The web app is internet-facing and proxies `/api/auth/**` (login, signup, resending the
verification code), which needs no authentication. `allowed_ingress_cidrs` renders one
`ip_security_restriction` block per entry:

```hcl
allowed_ingress_cidrs = ["203.0.113.42/32", "198.51.100.0/24"]
```

### Key Vault durability

The vault holds the only copy of the generated secrets. They're write-only, so not even state has
them. Losing the vault means bumping each version variable to issue new ones, which signs everybody
out.

The vault name has a random suffix, `kv-${env}-kanban-${suffix}`, like the Postgres server. Vault
names are globally unique, so a plain name could already belong to another tenant. Worse, a failed
apply soft-deletes the vault and reserves its name for the whole retention window, so every retry
would fail on the name for 90 days.

Soft delete is always on, but by itself it doesn't protect you, because the provider's
`purge_soft_delete_on_destroy` defaults to **true**: `terraform destroy` deletes the vault and purges
it in the same run. The `features` block in `providers.tf` turns that off unless an environment
asks for it.

| | dev | uat / prod templates |
|---|---|---|
| `key_vault_purge_protection_enabled` | `false` | `true` |
| `key_vault_purge_soft_delete_on_destroy` | `true` | `false` |
| `key_vault_soft_delete_retention_days` | 90 | 90 |

Dev is disposable on purpose: tearing it down purges the vault so nothing is left behind. In uat
and prod a destroy would leave a recoverable vault, and the next apply would pick it back up,
because the provider restores a soft-deleted vault instead of failing on the name.

Two of those settings can't be undone:

- **Purge protection can't be turned off.** Azure accepts an apply that enables it and rejects any
  that disables it. After that, the vault can't be removed before its retention window ends, not
  through Terraform, the portal or a support ticket.
- **`soft_delete_retention_days` is immutable.** The provider marks it ForceNew, so changing it plans
  a replacement, which on a purge-protected vault can't apply. I pin it to 90 (Azure's default and
  maximum) everywhere so no environment ever plans that.

### Network security groups

All six subnets have an NSG (`nsg-backend-${env}`, `nsg-db-${env}`, `nsg-pe-${env}`,
`nsg-storage-${env}`, `nsg-redis-${env}`, `nsg-monitoring-${env}`), defined in
[modules/vnet/nsg.tf](modules/vnet/nsg.tf) next to the subnets whose CIDRs they use.

They exist because of one of Azure's built-in rules: `AllowVnetInBound` at priority 65000 lets
anything in the VNet reach any port in any other subnet. Without NSGs, a foothold anywhere in
`snet-backend` could reach Postgres on 5432 directly. So each NSG denies inbound `VirtualNetwork`
traffic at priority 4096 and allows only the flows that should exist:

| | `snet-backend` | `snet-db` | `snet-pe` | `snet-storage` | `snet-redis` | `snet-monitoring` |
|---|---|---|---|---|---|---|
| 100 | 80/443 from `ingress_source_address_prefixes` | 5432 from `snet-backend` | 443 from `snet-backend` | 443 from `snet-backend` | 10000 from `snet-backend` | 443 from `snet-backend` |
| 110 | anything within the subnet | anything within the subnet | 443 from `snet-monitoring` | | | |
| 120 | 30000-32767 from `AzureLoadBalancer` | | | | | |
| 4096 | deny inbound from `VirtualNetwork` | same | same | same | same | same |

The deny's source is `VirtualNetwork` rather than `*`, so the platform's
`AllowAzureLoadBalancerInBound` at 65001 still applies. Rule 110 is required in both of those
subnets: Container Apps' own components talk across the infrastructure subnet on unpublished ports,
and the flexible server replicates to its standby inside the delegated subnet.

**The endpoint subnets' NSGs only work because network policies are on.** Azure doesn't apply NSGs
to private endpoint NICs by default, which is why `private_endpoint_network_policies` on `snet-pe`,
`snet-storage` and `snet-redis` is `NetworkSecurityGroupEnabled` instead of the default `Disabled`.
Set it back to `Disabled` and the NSG stays attached but silently stops being enforced.

`ingress_source_address_prefixes` defaults to `["Internet"]`, which the web app's public ingress
needs. Narrow it in a `*.tfvars` file for an environment that shouldn't be public. It only filters at
the subnet edge, so it's a blunt tool, not a WAF.

**Outbound is left on Azure's defaults, deliberately.** The app's egress doesn't fit into service
tags. It pulls images from `ghcr.io`, sends mail over HTTPS to the ACS Email API and verifies
captchas against `google.com`, on top of what the environment itself needs (MCR, Entra ID, Azure
Monitor, Azure Files on 445, NTP on UDP 123). A default-deny egress rule that misses one of those
shows up as a revision that never becomes healthy, not as a clear error. If I ever filter egress, it
will be behind a NAT gateway or firewall, not in these NSGs.

### Attachment storage

Task attachments go to a Storage account (`modules/storage`), not Postgres. Read this before
changing anything here, because three settings depend on each other.

**Nothing outside the VNet can reach it.** `public_network_access_enabled = false`, `network_rules`
default to `Deny`, and there's a private endpoint in `snet-storage` whose NSG allows 443 from the
backend subnet only. The `privatelink.blob.core.windows.net` zone linked to the VNet is what makes the
API resolve the account to that endpoint. The only thing that ever talks to the account is the API,
over that endpoint, as its own managed identity.

**That's why downloads go through the API.** A signed SAS would let the browser fetch a blob from
Azure directly, which is cheaper and keeps large transfers off small containers, but it needs the
account to accept traffic from anywhere a browser might be. With the account closed, `GET /api/tasks/{id}/attachments/{aid}/content` streams the file from storage
to the response. It streams rather than buffering, but it's still the API's CPU and bandwidth. If
attachment traffic ever outgrows that, the way out is Front Door with a private origin, not
reopening the account. [front-door-private-origin.md](front-door-private-origin.md) has that design,
the cheaper things to try first, and the traps, including why CDN caching in front of a SAS is a
security bug.

Checkov still skips two rules for this account: `CKV_AZURE_33` (queue logging, on an account with no
queues) and `CKV2_AZURE_1` (customer-managed keys).

**There's no account key.** `shared_access_key_enabled = false`, so the only way in is Entra ID. The
API authenticates as its user-assigned identity and reads and writes blobs as itself. Nothing about
the storage account is secret: no Key Vault entry, nothing in the template or state. The blob
endpoint and the identity's client id are plain environment variables taken from Terraform outputs.

| Principal | Role | Why |
|---|---|---|
| The API's user-assigned identity | `Storage Blob Data Contributor` | Reads and writes attachment blobs, and creates the container on first start |

It's Contributor rather than a narrower role because **Terraform doesn't create the blob
container**; the app does, on first start. Creating a container is a data-plane call, which from
Terraform would need either the account key or a blob data role for whoever runs `apply`. The
identity that writes the blobs can create the container itself, and doing it again is harmless. The
role assignment gets the same keyed `time_sleep` as the Key Vault ones (see
[Key Vault authorization](#key-vault-authorization)).

Things to know when operating it:

- **The account name has a random suffix and the resource is `prevent_destroy`.** Storage names are
  global, and a replaced account is a new empty one while every `task_attachments` row still points
  at the old blobs.
- **Replication is a variable.** `GRS` is the module default and what prod would use; dev and uat set
  `LRS`. Azure lets you change this later, so it doesn't have to be right on day one.
- **Blob soft delete matches the database's restore window.** `retention_days` comes from
  `postgres_backup_retention_days` instead of a number of its own. An attachment is half a row in
  Postgres and half a blob here, with no foreign key or transaction across them. The only way to roll
  both back to the same moment is to give them recovery windows of the same length. If this one were
  shorter, a database restored three weeks back would come up with rows pointing at purged blobs.
  `attachment_retention_days` separates them if you really want that.
- **Blobs are copied to another region.** The account has versioning and the change feed on, and
  `modules/backup` replicates its container into a GRS account in the backup resource group (Sweden
  Central), next to the database dumps. It's a storage-service copy, so it needs no VNet endpoint and
  nothing changes in the app. A delete replicates as "the current version became a previous one", so
  the copy keeps a deleted file for the same `retention_days`. The destination refuses writes while
  the policy exists; to restore, copy back from it or remove the policy first.

An environment with no storage configured still runs. The app starts, reports `attachments` as
`OUT_OF_SERVICE` on `/actuator/health`, and refuses uploads with a `503` saying storage isn't
configured. That's the same allowance mail makes, and it's what lets CI and a fresh clone run
without Azure.

### Rate limiter Redis

`modules/redis` provisions the **Azure Managed Redis** instance where `AuthRateLimiter` keeps its
escalation state, so the login and signup limits hold across API replicas. Classic Azure Cache for
Redis isn't an option, because Azure refuses to create one. Managed Redis is Redis Enterprise
underneath, still under `Microsoft.Cache`, but several things differ from a classic cache: the access-key and TLS
settings live on `default_database`, and the private endpoint's subresource and DNS zone are both
`redisEnterprise`.

**It's `Balanced_B0`, the smallest SKU there is, and that's fine.** It has no replica and no SLA
beyond the single node, and nothing needs more. `RedisEscalationStore` **fails open** whenever it
can't reach Redis or can't read its answer, so losing the cache only gives someone mid-escalation
their burst back. A bigger SKU would buy availability for state the code already treats as
expendable.

**It answers only the API.** `public_network_access = "Disabled"`, and there's a private endpoint in
`snet-redis` whose NSG allows port 10000 from the backend subnet. `client_protocol = "Encrypted"`
enforces TLS. The database listens on `10000`, not the `6379`/`6380` of a classic cache; the module's
`port` output carries that so nothing else hardcodes it.

**There's no access key.** `access_keys_authentication_enabled = false`, and the API logs in as its
managed identity. `modules/api_app` grants it an `azurerm_managed_redis_access_policy_assignment` on
the default database and passes its client id and object id as
`SECURITY_RATE_LIMIT_REDIS_ENTRA_CLIENT_ID` and `SECURITY_RATE_LIMIT_REDIS_USERNAME`.
`RedisRateLimitConfiguration` gives Jedis a token manager that fetches a token for
`https://redis.azure.com/.default` and renews it before it expires, so there's no key secret that a
regeneration could leave stale in a running revision. The API depends on the assignment, so a new revision never starts before its
identity is allowed in. With neither variable set (docker-compose, CI), the app connects without
authentication.

Locally and in CI, `security.rate-limit.redis-host` defaults to `localhost`. docker-compose runs a
plain `redis:7-alpine` with no password and no TLS, and the CI backend job runs one as a service
container. The escalation logic behaves the same; only the connection differs, which is what
`security.rate-limit.redis-ssl` switches.

### RabbitMQ broker

`modules/broker` runs the STOMP broker that `WebSocketConfig.configureMessageBroker` relays to. Every
API replica relays to this one broker, so a board event published on one replica reaches a browser
connected to another. It's a Container App,
`kanban-broker-<env>`, in the same environment as the web and API apps. An internal TCP ingress
that only apps in that environment can reach is a tighter boundary than a private endpoint, which
anything in the VNet can reach, and it needs no subnet of its own.

The decisions behind it:

- **Exactly one replica, unrelated to `api_max_replicas`.** A second unclustered RabbitMQ would just
  be another broker nobody relays between, which is the exact problem this module solves. Clustering
  RabbitMQ is a much bigger job, and I don't think this project needs it at its size. If the broker
  goes down, in-flight board and chat frames are lost, not data: nothing here is durable, and a
  reconnecting client re-subscribes and reloads.
- **The stock `rabbitmq:4-alpine` image, pinned by digest, with the STOMP plugin enabled at start.**
  A bare tag would let a restart pull a different image. Dependabot bumps the digest in
  `docker-compose.yml`, and `PinnedImagesTest` fails the build until this module names the same
  digest, so local and deployed stay in step. The container's `command` runs
  `rabbitmq-plugins enable --offline rabbitmq_stomp` and then hands over to the image's entrypoint.
  There is no custom image: one line isn't worth a third image in the CD build, scan and promote
  steps.
- **One account, `kanban`, for both the client and system logins.** RabbitMQ can't tell callers
  apart beyond the TCP connection, so separate credentials wouldn't buy anything. The password is
  generated and stored as the `RABBITMQ-PASSWORD` secret, and the broker's identity can read that one
  secret only.
- **Health checks are TCP only.** There's no HTTP endpoint, just port `61613`, so all three probes
  use `transport = "TCP"`. The startup probe is more patient than the other apps', because Erlang
  plus the plugin step starts slower than either JVM.

`api_app` takes `STOMP_RELAY_HOST`, `STOMP_RELAY_PORT`, `STOMP_RELAY_USERNAME` and
`STOMP_RELAY_PASSWORD` from this module's outputs. The root module's `depends_on` on `module.broker`
is there for the same reason as the ones on `module.redis` and `module.storage`: the password
reference is a string built from the vault URI, which Terraform's graph can't see.

### Key Vault authorization

The vault uses `rbac_authorization_enabled = true`, so **Azure RBAC is the only thing that grants
data-plane access**. Access policies are ignored. Two kinds of assignment do all the work:

| Principal | Role | Why |
|---|---|---|
| Whoever runs `terraform apply` | `Key Vault Secrets Officer` | Terraform writes the secrets |
| Each app's user-assigned identity | `Key Vault Secrets User` (scoped to single secrets where possible) | The app resolves its `secret { key_vault_secret_id = ... }` references when a revision starts |

Two consequences:

- **Control-plane roles don't give data-plane access.** Owner or Contributor on the resource group
  lets you manage the vault but not read its secrets. To run `az keyvault secret show` yourself,
  assign yourself `Key Vault Secrets User` or `Key Vault Secrets Officer` on the vault.
- **Role assignments take a while to propagate.** Each assignment is followed by a `time_sleep`
  before anything touches a secret, because Key Vault can still answer `403` for a grant Azure has
  already accepted. If an apply fails with `Forbidden` on `azurerm_key_vault_secret`, or the first
  revision won't start, running `terraform apply` again usually fixes it.

  The wait is `var.rbac_propagation_delay`, `60s` by default. It's a guess, because Azure gives no
  signal that a grant has arrived, so a slower tenant can raise it without editing a module.

  **Each wait is keyed on the id of the assignment it waits for.** That matters more than it looks.
  `depends_on` only orders a create; it doesn't make an existing resource run again. A plain wait
  keeps the timestamp of the first apply, and the applies that need the wait most are the ones where
  an assignment gets replaced (vault rebuilt, identity recreated, someone else applying). Keying on
  the assignment id recreates the wait whenever the grant is recreated.

### Monitoring VM

`monitoring_enabled` creates the Prometheus VM (`modules/monitoring_vm`) in `snet-monitoring-<env>`.
The VM is `10.0.3.52` and has no public IP. Its NSG allows one thing: 443 from the Container Apps
subnet. The API's metric push is a separate switch (`api_otlp_export_enabled`), because the VM has to
be configured before anything pushes to it.

Nobody logs in to it. It's configured with Run Command, which goes through Azure's control plane and
is authorized by an Azure role. Resource group Owners already have it, and
`monitoring_operator_object_ids` grants a narrow *Monitoring VM Operator* role to anyone else. Run
Command means root on the VM, so that role effectively includes the VM's Key Vault access too,
including replacing the CA the API trusts. Treat it like an admin role. The role grants Run Command
and nothing that stops or deallocates the VM.

```bash
az vm run-command invoke -g kanban-dev-rg -n vm-monitoring-dev --command-id RunShellScript --scripts "uptime"
```

Its identity reads two secrets, `MONITORING-PUSH-PASSWORD` and `MONITORING-READ-PASSWORD`, and
writes one, `MONITORING-CA-CERT`. The VM creates its own CA, so no TLS private key ever reaches
state. Each credential is stored once, and the API builds its push header from the same password
the VM reads, so they can't drift. Bump `credentials_version` on the module to rotate both.

`encryption_at_host_enabled` needs the `Microsoft.Compute/EncryptionAtHost` subscription feature.
Registering the resource provider doesn't turn it on, and `resource_providers_to_register` doesn't
cover it. On a fresh subscription, before the first apply:

```bash
az feature register --namespace Microsoft.Compute --name EncryptionAtHost
az provider register -n Microsoft.Compute   # once the feature shows Registered
```

The VM reaches apt and GitHub through Azure's default outbound access, since the VNet has no NAT
gateway. If Azure retires default outbound for the subnet, package and config updates stop working,
but the running stack keeps going.

### Grafana

`monitoring_enabled` also creates `kanban-grafana-<env>` (`modules/grafana_app`), a Container App
running `ghcr.io/<owner>/kanbanproject-grafana`, which CD builds from
`observability/grafana/Dockerfile` with the repo's dashboards baked in. It reads Prometheus through
the VM's nginx with the read credential, and Azure Monitor with its own managed identity. It scales
to zero when nobody's looking.

There are two sign-ins, but users only see one:

1. The Container Apps ingress requires an Entra sign-in before any request reaches Grafana, so
   Grafana itself is never exposed to anonymous traffic. Browsers get redirected to Microsoft;
   anything else gets a 401.
2. Grafana's own Entra login runs behind it on the same session and takes the user's role from the
   token. It authenticates as the app's managed identity through a federated credential, so there's
   no client secret to rotate.

The tenant is on the free Entra tier, which can't assign groups to app roles. So each person gets a
role directly, and assignment is required, which means anyone unassigned can't get a token at all:

```bash
terraform/scripts/grafana-role.sh someone@example.com Viewer   # or Editor, Admin
terraform/scripts/grafana-role.sh someone@example.com none     # remove access
```

Editor means Explore and scratch dashboards. The provisioned dashboards only change through the
repo.

The Grafana image skips Trivy for `usr/share/grafana/bin/grafana` only, since those findings clear
only when Grafana Labs ships a release. Every package the image can upgrade is still checked.
