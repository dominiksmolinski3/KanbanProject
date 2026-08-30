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
state_key="env/${env_name}/terraform.tfstate"

[ -f "$var_file" ] || { echo "error: $var_file not found" >&2; exit 1; }

echo "==> ${env_name}: state ${state_key}, vars ${var_file}"

terraform init -reconfigure -backend-config="key=${state_key}"

subcommand=$1
shift

# apply and destroy take a saved plan file rather than -var-file; passing both is an error.
case "$subcommand" in
  plan|refresh|import|console)
    exec terraform "$subcommand" -var-file="$var_file" "$@"
    ;;
  apply|destroy)
    if [ $# -gt 0 ] && [ -f "$1" ]; then
      exec terraform "$subcommand" "$@"
    fi
    exec terraform "$subcommand" -var-file="$var_file" "$@"
    ;;
  *)
    exec terraform "$subcommand" "$@"
    ;;
esac
