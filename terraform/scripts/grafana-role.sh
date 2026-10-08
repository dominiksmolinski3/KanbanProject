#!/usr/bin/env bash

# Usage: terraform/scripts/grafana-role.sh <user@domain> <Viewer|Editor|Admin|none>
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: $0 <user principal name> <Viewer|Editor|Admin|none>" >&2; exit 2; }
user=$1
role=$2
env_name=${ENV_NAME:-dev}

case "$role" in
  Viewer | Editor | Admin | none) ;;
  *) echo "error: role must be Viewer, Editor, Admin or none" >&2; exit 2 ;;
esac

graph=https://graph.microsoft.com/v1.0
sp=$(az ad sp list --display-name "kanban-grafana-${env_name}" --query "[0].id" -o tsv)
[ -n "$sp" ] || { echo "error: no kanban-grafana-${env_name} service principal; is Grafana deployed?" >&2; exit 1; }
principal=$(az ad user show --id "$user" --query id -o tsv)

for assignment in $(az rest --method get --url "${graph}/servicePrincipals/${sp}/appRoleAssignedTo" \
    --query "value[?principalId=='${principal}'].id" -o tsv); do
  az rest --method delete --url "${graph}/servicePrincipals/${sp}/appRoleAssignedTo/${assignment}"
done

if [ "$role" = "none" ]; then
  echo "${user} no longer has a Grafana role"
  exit 0
fi

role_id=$(az ad sp show --id "$sp" --query "appRoles[?value=='${role}'].id | [0]" -o tsv)
az rest --method post --url "${graph}/servicePrincipals/${sp}/appRoleAssignedTo" \
  --headers "Content-Type=application/json" \
  --body "{\"principalId\":\"${principal}\",\"resourceId\":\"${sp}\",\"appRoleId\":\"${role_id}\"}" \
  --query "appRoleId" -o none
echo "${user} is now a Grafana ${role}"
