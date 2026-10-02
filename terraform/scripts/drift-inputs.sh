#!/usr/bin/env bash

# Usage: scripts/drift-inputs.sh <env> <out.tfvars.json>
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: $0 <env> <out.tfvars.json>" >&2; exit 2; }
env_name=$1
out=$2

var_file="$(dirname "$0")/../${env_name}.tfvars"
[ -f "$var_file" ] || { echo "error: $var_file not found" >&2; exit 1; }

tfvar() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$var_file" | tail -1
}

resource_group=$(tfvar resource_group_name)
env=$(tfvar env)
api_app="kanban-api-${env}"

vault=$(az keyvault list --resource-group "$resource_group" --query "[0].name" --output tsv)
[ -n "$vault" ] || { echo "error: no Key Vault in $resource_group" >&2; exit 1; }

allowed_ips=$(az keyvault show --name "$vault" --query "properties.networkAcls.ipRules[].value" --output json \
  | jq -c --arg runner "${KEY_VAULT_RUNNER_IP:-}" '[.[] | sub("/32$"; "") | select(. != $runner)]')

image=$(az containerapp show --resource-group "$resource_group" --name "$api_app" \
  --query "properties.template.containers[0].image" --output tsv)
image_tag=${image##*:}
printf '%s' "$image_tag" | grep -Eq '^[0-9a-f]{40}$' \
  || { echo "error: $api_app runs '$image', which is not pinned to a commit SHA" >&2; exit 1; }

captcha_enabled=$(az containerapp show --resource-group "$resource_group" --name "$api_app" \
  --query "properties.template.containers[0].env[?name=='CAPTCHA_ENABLED'].value | [0]" --output tsv)
[ -n "$captcha_enabled" ] || { echo "error: $api_app has no CAPTCHA_ENABLED" >&2; exit 1; }

alert_email=$(az monitor action-group list --resource-group "$resource_group" \
  --query "[?name=='ag-kanban-${env}'].emailReceivers[0].emailAddress | [0]" --output tsv)

secret_names=""
for attempt in $(seq 12); do
  if secret_names=$(az keyvault secret list --vault-name "$vault" --query "[].name" --output tsv 2>/dev/null); then
    break
  fi
  [ "$attempt" -eq 12 ] && { echo "error: $vault refused to list secrets for two minutes" >&2; exit 1; }
  sleep 10
done

secret() {
  if printf '%s\n' "$secret_names" | grep -qx "$1"; then
    az keyvault secret show --vault-name "$vault" --name "$1" --query value --output tsv
  fi
}

captcha_secret=$(secret CAPTCHA-SECRET)
mail_delivery_report_key=$(secret APP-MAIL-DELIVERY-REPORT-KEY)

if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  for value in "$captcha_secret" "$mail_delivery_report_key" "$alert_email"; do
    [ -n "$value" ] && echo "::add-mask::$value"
  done
fi

umask 077
jq -n \
  --argjson key_vault_allowed_ips "$allowed_ips" \
  --arg app_image_tag "$image_tag" \
  --argjson captcha_enabled "$captcha_enabled" \
  --arg captcha_secret "$captcha_secret" \
  --arg alert_email "$alert_email" \
  --arg mail_delivery_report_key "$mail_delivery_report_key" \
  '$ARGS.named' > "$out"
