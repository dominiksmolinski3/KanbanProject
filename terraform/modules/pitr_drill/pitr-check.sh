set -eu
trap 'touch /drill/done' EXIT

name=$(cat /drill/server)
export PGHOST="$name.postgres.database.azure.com"

PGPASSWORD=$(wget -qO- --header "X-IDENTITY-HEADER: $IDENTITY_HEADER" \
  "$IDENTITY_ENDPOINT?api-version=2019-08-01&resource=https://ossrdbms-aad.database.windows.net&client_id=$AZURE_CLIENT_ID" |
  sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')
[ -n "$PGPASSWORD" ]
export PGPASSWORD

q() { psql --no-align --tuples-only --command="$1"; }
schema=$(q "select max(installed_rank) from flyway_schema_history where success")
[ -n "$schema" ]

echo "PITR_OK $name point=$(cat /drill/point) migrations=$schema users=$(q 'select count(*) from users') boards=$(q 'select count(*) from boards') tasks=$(q 'select count(*) from task')"
