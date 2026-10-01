set -eu

token=$(wget -qO- --header "X-IDENTITY-HEADER: $IDENTITY_HEADER" \
  "$IDENTITY_ENDPOINT?api-version=2019-08-01&resource=https://ossrdbms-aad.database.windows.net&client_id=$AZURE_CLIENT_ID" |
  sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')
[ -n "$token" ]

as_admin() {
  PGUSER=$ADMIN_ROLE PGPASSWORD=$token psql -v ON_ERROR_STOP=1 -v owner="$OWNER_ROLE" -v writer="$WRITER_ROLE" \
    -v reader="$READER_ROLE" -v psqladmin="$POSTGRES_USER" "$@"
}

for principal in "$OWNER_ROLE:$OWNER_OID" "$WRITER_ROLE:$WRITER_OID" "$READER_ROLE:$READER_OID"; do
  as_admin --dbname=postgres -v role="${principal%%:*}" -v oid="${principal#*:}" <<'SQL'
select pgaadauth_create_principal_with_oid(:'role', :'oid', 'service', false, false)
where not exists (select from pg_roles where rolname = :'role');
SQL
done

# The admin creates the group roles so it holds ADMIN on them, and can grant and revoke them with password login off.
as_admin <<'SQL'
select format('create role %I nologin', name)
from unnest(array['kanban_owner', 'kanban_writer', 'kanban_reader']) as name
where not exists (select from pg_roles where rolname = name)
\gexec
grant kanban_owner to current_user with inherit true, set true;
grant kanban_owner to :"psqladmin";
SQL

if [ "$PASSWORD_LOGIN" = true ]; then
  # Only the role that owns a table can hand it on, and the first migrations ran as the password admin.
  PGUSER=$POSTGRES_USER PGPASSWORD=$POSTGRES_PASSWORD psql -v ON_ERROR_STOP=1 <<'SQL'
grant all on schema public to kanban_owner;

select format('alter %s public.%I owner to kanban_owner',
              case c.relkind when 'S' then 'sequence' when 'v' then 'view' when 'm' then 'materialized view' else 'table' end,
              c.relname)
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relkind in ('r', 'p', 'v', 'm', 'S')
  and pg_get_userbyid(c.relowner) = current_user
  and not (c.relkind = 'S' and exists (select from pg_depend d where d.objid = c.oid and d.deptype in ('a', 'i')))
order by c.relkind = 'S'
\gexec
SQL
fi

as_admin <<'SQL'
select count(*) > 0 as foreign_owned
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'S')
  and pg_get_userbyid(c.relowner) <> 'kanban_owner'
\gset
\if :foreign_owned
do $$ begin raise exception 'tables in public are not owned by kanban_owner; run this job once with password login on'; end $$;
\endif

grant select, insert, update, delete on all tables in schema public to kanban_writer;
grant usage, select, update on all sequences in schema public to kanban_writer;
grant select on all tables in schema public to kanban_reader;
grant select on all sequences in schema public to kanban_reader;
alter default privileges for role kanban_owner in schema public grant select, insert, update, delete on tables to kanban_writer;
alter default privileges for role kanban_owner in schema public grant usage, select, update on sequences to kanban_writer;
alter default privileges for role kanban_owner in schema public grant select on tables to kanban_reader;
alter default privileges for role kanban_owner in schema public grant select on sequences to kanban_reader;

grant kanban_owner to :"owner";
grant kanban_writer to :"writer";
grant kanban_reader to :"reader";
revoke kanban_owner from :"writer";
SQL

echo "DB_ROLES_OK owner=$OWNER_ROLE writer=$WRITER_ROLE reader=$READER_ROLE"
