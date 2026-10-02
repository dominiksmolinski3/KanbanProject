set -eu

token=$(curl -fsS -H "X-IDENTITY-HEADER: $IDENTITY_HEADER" \
  "$IDENTITY_ENDPOINT?api-version=2019-08-01&resource=https://management.azure.com/&client_id=$AZURE_CLIENT_ID" |
  sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')
[ -n "$token" ]

api="api-version=2024-08-01"
servers="https://management.azure.com$SCRATCH_GROUP_ID/providers/Microsoft.DBforPostgreSQL/flexibleServers"

arm() {
  method=$1
  shift
  curl -fsS -X "$method" -H "Authorization: Bearer $token" -H "Content-Type: application/json" "$@"
}

state() {
  arm GET "$servers/$1?$api" 2>/dev/null | sed -n 's/.*"state" *: *"\([^"]*\)".*/\1/p'
}

wait_for() {
  for _ in $(seq 120); do
    [ "$(state "$1")" = "$2" ] && return 0
    sleep 30
  done
  echo "$1 did not reach '$2' in an hour" >&2
  return 1
}

remove() {
  arm DELETE "$servers/$1?$api" -o /dev/null
  wait_for "$1" ""
  echo "PITR_REMOVED $1"
}

case "$1" in
  restore)
    echo "PITR_START"

    for left in $(arm GET "$servers?$api" | grep -o '"name" *: *"psql-[^"]*-pitr-[^"]*"' | sed 's/.*"\(psql-[^"]*\)"/\1/'); do
      remove "$left"
    done

    name="psql-$ENV_NAME-pitr-$(date -u +%Y%m%d%H%M)"
    point=$(date -u -d "@$(($(date +%s) - 3600))" +%Y-%m-%dT%H:%M:%SZ)
    echo "$name" > /drill/server
    echo "$point" > /drill/point
    trap 'remove "$name"' EXIT

    arm PUT "$servers/$name?$api" -o /dev/null --data "{
      \"location\": \"$LOCATION\",
      \"tags\": {\"purpose\": \"pitr-drill\"},
      \"properties\": {
        \"createMode\": \"PointInTimeRestore\",
        \"sourceServerResourceId\": \"$SOURCE_SERVER_ID\",
        \"pointInTimeUTC\": \"$point\"
      }
    }"
    wait_for "$name" Ready

    arm GET "$servers/$name?$api" | grep -q '"activeDirectoryAuth" *: *"Enabled"' || {
      echo "$name came back without Entra auth" >&2
      exit 1
    }

    trap - EXIT
    ;;
  cleanup)
    for _ in $(seq 120); do
      [ -e /drill/done ] && break
      sleep 15
    done
    remove "$(cat /drill/server)"
    ;;
  *)
    echo "usage: restore | cleanup" >&2
    exit 2
    ;;
esac
