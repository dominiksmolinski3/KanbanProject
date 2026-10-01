set -eu

[ -s /backup/kanban.dump ]
name=$(cat /backup/name)

export PGDATA=/tmp/pgdata PGUSER=postgres
gosu postgres initdb --auth=trust --no-sync >/dev/null
gosu postgres pg_ctl --wait --options="-c listen_addresses=''" start >/dev/null
createdb kanban

pg_restore --no-owner --no-acl --dbname=kanban /backup/kanban.dump

q() { psql --dbname=kanban --no-align --tuples-only --command="$1"; }
schema=$(q "select max(installed_rank) from flyway_schema_history where success")
[ -n "$schema" ]

echo "RESTORE_OK $name migrations=$schema users=$(q 'select count(*) from users') boards=$(q 'select count(*) from boards') tasks=$(q 'select count(*) from task')"
