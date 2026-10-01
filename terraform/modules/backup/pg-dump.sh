set -eu

PGPASSWORD=$(wget -qO- --header "X-IDENTITY-HEADER: $IDENTITY_HEADER" \
  "$IDENTITY_ENDPOINT?api-version=2019-08-01&resource=https://ossrdbms-aad.database.windows.net&client_id=$AZURE_CLIENT_ID" |
  sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')
[ -n "$PGPASSWORD" ]
export PGPASSWORD

pg_dump --format=custom --file=/backup/kanban.dump
