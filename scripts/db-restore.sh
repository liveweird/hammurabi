#!/usr/bin/env bash
# Restore a scripts/db-backup.sh dump into the Hammurabi database.
#
#   scripts/db-restore.sh DUMP.dump [--replace] [--force-connected]
#
# SAFE BY DEFAULT: the target database must be EMPTY (no table, view or sequence outside the system schemas) and have no
# other client connected (the app, a psql); otherwise the script refuses and changes nothing. A database the app has
# already booted against is NOT empty (Flyway and the seed admin ran), so restore into a stack where only Postgres is up,
# THEN start the app (it finds the schema current). Stop the app first either way.
#
# --replace restores over a non-empty database and DESTROYS its current contents: in ONE transaction it drops every
# extension and every non-system schema (public, ops, ...) with CASCADE, recreates an empty `public`, then replays the dump
# (which recreates the extensions and schemas). The result is exactly the dump, even when the live database is ahead of it
# (newer tables, newer Flyway history). It is all or nothing: a failure or an interrupted stream rolls everything back.
# --force-connected lets the restore proceed while other clients are connected (they are not disconnected; --replace will
# wait on their locks or fail); it is for a client you know is idle.
#
# HAMMURABI_PG_EXEC, HAMMURABI_DB_USER and HAMMURABI_DB_NAME work as in scripts/db-backup.sh (compose, another compose project,
# `kubectl -n hammurabi exec -i deploy/postgres --`). Runbook: .claude/docs/persistence.md "Backup and restore".
# A variable that is SET BUT EMPTY is refused, never read as "unset" (a computed command that came out blank must not fall back to the
# local default container, which holds real data). The target command is printed on stderr before anything runs. (A deliberate
# departure from Adder's scripts.) "Empty" means no relation, no extension besides plpgsql and no schema besides public.
set -euo pipefail

db_user=${HAMMURABI_DB_USER:-hammurabi}
db_name=${HAMMURABI_DB_NAME:-hammurabi}
exec_cmd=${HAMMURABI_PG_EXEC-docker exec -i hammurabi-postgres}
if [ -z "${exec_cmd//[[:space:]]/}" ]; then
    echo "refusing: HAMMURABI_PG_EXEC is set but empty (unset it for the default compose container, or give the command)" >&2
    exit 2
fi
read -r -a pg_exec <<<"$exec_cmd"

usage="usage: $0 DUMP.dump [--replace] [--force-connected]   (see the header of this script)"
dump=""
replace=0
force_connected=0
for arg in "$@"; do
    case "$arg" in
        --replace) replace=1 ;;
        --force-connected) force_connected=1 ;;
        -h | --help) echo "$usage"; exit 0 ;;
        -*) echo "unknown option: $arg" >&2; exit 2 ;;
        *) if [ -n "$dump" ]; then echo "only one dump file" >&2; exit 2; fi; dump=$arg ;;
    esac
done
if [ -z "$dump" ]; then
    echo "$usage" >&2
    exit 2
fi
if [ ! -s "$dump" ]; then
    echo "no such dump (or empty): $dump" >&2
    exit 1
fi

psql_q() { "${pg_exec[@]}" psql -U "$db_user" -d "$db_name" -v ON_ERROR_STOP=1 -qtAc "$1"; }

echo "restoring $dump into '$db_name' via: $exec_cmd" >&2

# The archive must be readable before anything is touched.
"${pg_exec[@]}" pg_restore --list <"$dump" >/dev/null

# A fresh volume can pass the container's healthcheck (pg_isready on the socket) while the entrypoint's temporary init server is still
# up and about to be replaced: wait, up to ~30 s, for a real query to answer before looking at the database.
ready=0
for _ in $(seq 1 30); do
    if psql_q "select 1" >/dev/null 2>&1; then ready=1; break; fi
    sleep 1
done
if [ "$ready" -ne 1 ]; then
    echo "refusing: '$db_name' did not answer a query within 30 s via: $exec_cmd" >&2
    exit 1
fi

clients=$(psql_q "select count(*) from pg_stat_activity where datname = current_database() and pid <> pg_backend_pid() and backend_type = 'client backend'")
if [ "$clients" -gt 0 ] && [ "$force_connected" -ne 1 ]; then
    echo "refusing: $clients other client(s) are connected to '$db_name' (is the app still running? stop it first)." >&2
    echo "pass --force-connected only for a client you know is idle." >&2
    exit 1
fi

relations=$(psql_q "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast') and c.relkind in ('r', 'p', 'v', 'm', 'S', 'f')")
extensions=$(psql_q "select count(*) from pg_extension where extname <> 'plpgsql'")
schemas=$(psql_q "select count(*) from pg_namespace where nspname !~ '^pg_' and nspname not in ('information_schema', 'public')")
found="$relations relations, $extensions extensions, $schemas extra schemas"

if [ $((relations + extensions + schemas)) -gt 0 ] && [ "$replace" -ne 1 ]; then
    echo "refusing: database '$db_name' is not empty ($found). Start only Postgres on a fresh volume," >&2
    echo "or pass --replace to overwrite it (this destroys everything currently in the database)." >&2
    exit 1
fi

if [ "$replace" -eq 1 ] && [ $((relations + extensions + schemas)) -gt 0 ]; then
    echo "--replace: DROPPING every schema and extension of '$db_name' ($found) via: $exec_cmd" >&2
    # The whole replacement is one explicit transaction fed to psql WITHOUT -1: if the stream is cut short, psql exits with the
    # transaction open and the server rolls it back (with -1 a truncated script would COMMIT the half-restore). The SQL is
    # rendered to a private temp file first, so a failed pg_restore never reaches the database.
    umask 077
    script=$(mktemp)
    trap 'rm -f "$script"' EXIT
    {
        cat <<'SQL'
BEGIN;
SET LOCAL client_min_messages = warning;
DO $drop$
DECLARE r record;
BEGIN
    FOR r IN SELECT extname FROM pg_extension WHERE extname <> 'plpgsql' LOOP
        EXECUTE format('DROP EXTENSION %I CASCADE', r.extname);
    END LOOP;
    FOR r IN SELECT nspname FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname <> 'information_schema' LOOP
        EXECUTE format('DROP SCHEMA %I CASCADE', r.nspname);
    END LOOP;
    CREATE SCHEMA public AUTHORIZATION pg_database_owner;
    GRANT USAGE ON SCHEMA public TO PUBLIC;
    COMMENT ON SCHEMA public IS 'standard public schema';
END
$drop$;
SQL
        "${pg_exec[@]}" pg_restore --no-owner --no-acl -f - <"$dump"
        echo "COMMIT;"
    } >"$script"
    "${pg_exec[@]}" psql -U "$db_user" -d "$db_name" -v ON_ERROR_STOP=1 -q -f - <"$script" >/dev/null
else
    "${pg_exec[@]}" pg_restore --no-owner --no-acl --single-transaction --exit-on-error -U "$db_user" -d "$db_name" <"$dump"
fi

version=$(psql_q "select coalesce(max(version::int)::text, 'none') from flyway_schema_history where success" || echo unknown)
echo "restored $dump into '$db_name'; schema at Flyway version $version"
