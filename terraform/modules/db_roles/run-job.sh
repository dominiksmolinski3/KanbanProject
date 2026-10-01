set -euo pipefail

execution=$(az containerapp job start --name "$JOB" --resource-group "$RESOURCE_GROUP" --query name --output tsv)

for _ in $(seq 60); do
  status=$(az containerapp job execution show --name "$JOB" --resource-group "$RESOURCE_GROUP" \
    --job-execution-name "$execution" --query properties.status --output tsv)
  case "$status" in
    Succeeded) exit 0 ;;
    Failed | Stopped | Degraded)
      echo "$JOB execution $execution ended $status; its console log is in ContainerAppConsoleLogs_CL" >&2
      exit 1
      ;;
  esac
  sleep 10
done

echo "$JOB execution $execution did not finish in ten minutes" >&2
exit 1
