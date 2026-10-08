#!/usr/bin/env bash

# Usage: ansible/scripts/converge.sh [<commit>] [--check]
set -euo pipefail

env_name=${ENV_NAME:-dev}
resource_group=${RESOURCE_GROUP:-kanban-${env_name}-rg}
vm_name=${VM_NAME:-vm-monitoring-${env_name}}
repository=${REPOSITORY:-https://github.com/dominiksmolinski3/KanbanProject.git}

commit=""
check_args=""
for arg in "$@"; do
  case "$arg" in
    --check) check_args="--check --diff" ;;
    *) commit=$arg ;;
  esac
done
if [ -z "$commit" ]; then
  commit=$(git rev-parse origin/main)
fi
case "$commit" in
  *[!0-9a-f]* | "") echo "error: '$commit' is not a commit id" >&2; exit 2 ;;
esac

key_vault_name=$(az keyvault list -g "$resource_group" --query "[0].name" -o tsv)
identity_client_id=$(az identity show -g "$resource_group" -n "kanban-monitoring-identity-${env_name}" --query clientId -o tsv)

script=$(cat <<EOF
set -eu
export DEBIAN_FRONTEND=noninteractive
if ! command -v ansible-pull >/dev/null || ! command -v git >/dev/null; then
  apt-get update -q >/dev/null
  apt-get install -yq ansible git >/dev/null
fi
export ANSIBLE_DISPLAY_OK_HOSTS=false ANSIBLE_DISPLAY_SKIPPED_HOSTS=false ANSIBLE_NOCOLOR=1
ansible-pull -U "$repository" -C "$commit" -d /opt/kanban-config -i localhost, \
  -e key_vault_name=$key_vault_name -e identity_client_id=$identity_client_id \
  $check_args ansible/site.yml 2>&1 | tee /var/log/kanban-converge.log | tail -c 3500
EOF
)

echo "==> converging ${vm_name} at ${commit}${check_args:+ (check mode)}"
output=$(az vm run-command invoke -g "$resource_group" -n "$vm_name" --command-id RunShellScript \
  --scripts "$script" --query "value[0].message" -o tsv)
echo "$output"

recap=$(echo "$output" | grep -E '^localhost +: ok=' || true)
if [ -z "$recap" ]; then
  echo "error: no play recap; the run did not finish (full log: /var/log/kanban-converge.log on the VM)" >&2
  exit 1
fi
if ! echo "$recap" | grep -qE 'failed=0 .*' || ! echo "$recap" | grep -qE 'unreachable=0'; then
  echo "error: the converge failed" >&2
  exit 1
fi
