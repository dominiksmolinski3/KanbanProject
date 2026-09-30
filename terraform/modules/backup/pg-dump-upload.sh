set -eu

[ -s /backup/kanban.dump ]

token=$(curl -fsS -H "X-IDENTITY-HEADER: $IDENTITY_HEADER" \
  "$IDENTITY_ENDPOINT?api-version=2019-08-01&resource=https://storage.azure.com/&client_id=$AZURE_CLIENT_ID" |
  sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')
[ -n "$token" ]

name="kanban-$(date -u +%Y%m%dT%H%M%SZ).dump"

put() {
  curl -fsS -X PUT -T /backup/kanban.dump \
    -H "Authorization: Bearer $token" \
    -H "x-ms-version: 2023-11-03" \
    -H "x-ms-blob-type: BlockBlob" \
    -H "Content-Type: application/octet-stream" \
    "$CONTAINER_URL/$1/$name"
}

put daily
if [ "$(date -u +%u)" = 7 ]; then put weekly; fi
if [ "$(date -u +%d)" = 01 ]; then put monthly; fi

echo "BACKUP_OK $name $(wc -c < /backup/kanban.dump) bytes"
