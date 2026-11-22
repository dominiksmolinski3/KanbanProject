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

container=$(az containerapp show --resource-group "$resource_group" --name "$api_app" \
  --query "properties.template.containers[0]" --output json)

container_env() {
  printf '%s' "$container" | jq -r --arg name "$1" '[.env[]? | select(.name == $name) | .value][0] // ""'
}

image=$(printf '%s' "$container" | jq -r '.image')
image_tag=${image##*:}
printf '%s' "$image_tag" | grep -Eq '^[0-9a-f]{40}$' \
  || { echo "error: $api_app runs '$image', which is not pinned to a commit SHA" >&2; exit 1; }

captcha_enabled=$(container_env CAPTCHA_ENABLED)
[ -n "$captcha_enabled" ] || { echo "error: $api_app has no CAPTCHA_ENABLED" >&2; exit 1; }

push_vapid_public_key=$(container_env VAPID_PUBLIC_KEY)
push_vapid_subject=$(container_env VAPID_SUBJECT)

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

# JSON, not tsv: the FCM key is multi-line JSON, which $(... --output tsv) would not return byte for byte.
secret() {
  if printf '%s\n' "$secret_names" | grep -qx "$1"; then
    az keyvault secret show --vault-name "$vault" --name "$1" --query value --output json
  else
    printf '""'
  fi
}

captcha_secret=$(secret CAPTCHA-SECRET)
mail_delivery_report_key=$(secret APP-MAIL-DELIVERY-REPORT-KEY)
push_vapid_private_key=$(secret VAPID-PRIVATE-KEY)
push_fcm_credentials=$(secret FCM-CREDENTIALS)

if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  # add-mask masks one line, so a multi-line secret is masked line by line.
  jq -rn \
    --argjson a "$captcha_secret" --argjson b "$mail_delivery_report_key" \
    --argjson c "$push_vapid_private_key" --argjson d "$push_fcm_credentials" \
    --arg e "$alert_email" --arg f "$push_vapid_subject" \
    '$ARGS.named[] | split("\n")[] | select(length >= 8)' \
    | while IFS= read -r line; do echo "::add-mask::$line"; done
fi

umask 077
jq -n \
  --argjson key_vault_allowed_ips "$allowed_ips" \
  --arg app_image_tag "$image_tag" \
  --argjson captcha_enabled "$captcha_enabled" \
  --argjson captcha_secret "$captcha_secret" \
  --arg alert_email "$alert_email" \
  --argjson mail_delivery_report_key "$mail_delivery_report_key" \
  --arg push_vapid_public_key "$push_vapid_public_key" \
  --argjson push_vapid_private_key "$push_vapid_private_key" \
  --arg push_vapid_subject "$push_vapid_subject" \
  --argjson push_fcm_credentials "$push_fcm_credentials" \
  '$ARGS.named' > "$out"
