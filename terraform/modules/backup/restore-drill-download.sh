set -eu

token=$(curl -fsS -H "X-IDENTITY-HEADER: $IDENTITY_HEADER" \
  "$IDENTITY_ENDPOINT?api-version=2019-08-01&resource=https://storage.azure.com/&client_id=$AZURE_CLIENT_ID" |
  sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')
[ -n "$token" ]

get() {
  curl -fsS -H "Authorization: Bearer $token" -H "x-ms-version: 2023-11-03" "$@"
}

name=$(get "$CONTAINER_URL?restype=container&comp=list&prefix=daily/" |
  grep -o '<Name>daily/[^<]*</Name>' | sed 's/<[^>]*>//g' | sort | tail -n 1)
[ -n "$name" ]

get -o /backup/kanban.dump "$CONTAINER_URL/$name"
echo "$name" > /backup/name
