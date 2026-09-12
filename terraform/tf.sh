#!/usr/bin/env bash
#
# Pairs the backend state key with the -var-file, which is the one thing nothing else does.
#
# The environments are separated by state key alone, and the key lives in a flag rather than in
# providers.tf. Passing `key=env/prod/terraform.tfstate` next to `-var-file dev.tfvars` produces a
# plan that reads as a legitimate rename - destroy the prod resource group, create the dev one -
# and there is no workspace, no CI check and no assertion that catches it. Here both come from the
# same argument, so they cannot disagree.
#
#   ./tf.sh dev plan
#   ./tf.sh prod plan -out=prod.tfplan
#   ./tf.sh prod apply prod.tfplan
#
# `init -reconfigure` runs first every time: switching environments without it leaves the previous
# environment's backend configured, which is the other half of the same mistake.
#
# ---------------------------------------------------------------------------------------------
# The local overrides are paired here too, and they used not to be.
#
# Secrets that must not be committed - the ACS connection string, the captcha secret, the alert
# address, a workstation's own IP for the Key Vault firewall - live in a gitignored file beside the
# committed per-environment tfvars. That file was named `<env>.local.auto.tfvars`, and **Terraform
# loads every *.auto.tfvars in the working directory on every single run**, whatever -var-file is
# passed. So the pairing this script exists to guarantee had a hole straight through it.
#
# The committed var-file wins for every variable it sets, which is why this was invisible: `env`,
# the SKUs, the retention days all came out right. What it does not set is precisely the secret-
# bearing set, because those have no committed value - so a prod plan run from a workstation
# holding dev's local file took dev's ACS connection string into the prod Key Vault secret, dev's
# captcha secret with it, dev's alert address onto the prod alerts, and a developer's home IP onto
# the prod vault firewall. Measured, not reasoned: with `prod.tfvars` setting `env` and a
# `dev.local.auto.tfvars` setting both `env` and a second variable, `terraform console` answers
# "prod" for the first and the dev value for the second.
#
# So the file is now `<env>.local.tfvars` - no `.auto.` - and it is passed here, after the
# committed one so that it still overrides it. A file for an environment you are not running is
# now simply not read. Any leftover `*.local.auto.tfvars` stops this script outright rather than
# being quietly loaded alongside, because the whole point is that it is no longer a file anything
# picks up by itself.

set -euo pipefail

ENVIRONMENTS="dev uat prod"

usage() {
  echo "usage: $0 <${ENVIRONMENTS// /|}> <terraform-subcommand> [args...]" >&2
  exit 2
}

[ $# -ge 2 ] || usage

env_name=$1
shift

case " $ENVIRONMENTS " in
  *" $env_name "*) ;;
  *) echo "error: unknown environment '$env_name'" >&2; usage ;;
esac

cd "$(dirname "$0")"

var_file="${env_name}.tfvars"
local_var_file="${env_name}.local.tfvars"
state_key="env/${env_name}/terraform.tfstate"

[ -f "$var_file" ] || { echo "error: $var_file not found" >&2; exit 1; }

# Refuse rather than load it as well: see the note at the top of this file. A `.auto.` file is
# read by Terraform itself on every run, so leaving one in place would mean the rename bought
# nothing and the leak was still available.
legacy=$(ls ./*.local.auto.tfvars 2>/dev/null || true)
if [ -n "$legacy" ]; then
  echo "error: Terraform auto-loads these on every run, whatever environment you asked for:" >&2
  echo "$legacy" | sed 's/^/  /' >&2
  echo "rename each to <env>.local.tfvars (drop the '.auto.'); this script passes the one that matches." >&2
  exit 1
fi

var_file_args=(-var-file="$var_file")
if [ -f "$local_var_file" ]; then
  # After the committed file, so it still overrides it - the later -var-file wins.
  var_file_args+=(-var-file="$local_var_file")
  echo "==> ${env_name}: state ${state_key}, vars ${var_file} + ${local_var_file}"
else
  echo "==> ${env_name}: state ${state_key}, vars ${var_file}"
fi

terraform init -reconfigure -backend-config="key=${state_key}"

subcommand=$1
shift

# apply and destroy take a saved plan file rather than -var-file; passing both is an error.
case "$subcommand" in
  plan|refresh|import|console)
    exec terraform "$subcommand" "${var_file_args[@]}" "$@"
    ;;
  apply|destroy)
    if [ $# -gt 0 ] && [ -f "$1" ]; then
      exec terraform "$subcommand" "$@"
    fi
    exec terraform "$subcommand" "${var_file_args[@]}" "$@"
    ;;
  *)
    exec terraform "$subcommand" "$@"
    ;;
esac
