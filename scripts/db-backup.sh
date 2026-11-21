#!/usr/bin/env bash
# Dump the Hammurabi database (pg_dump, custom format) to a file on this machine.
#
#   scripts/db-backup.sh [OUTPUT.dump]
#
# The dump is taken INSIDE the Postgres container/pod (pg_dump needs no password over its local socket), streamed
# through HAMMURABI_PG_EXEC and written here with mode 0600: it holds every user, ADR and password hash, so treat
# the file like the database itself. It never overwrites an existing file. The default name is hammurabi-<UTC time>.dump
# in the current directory. A consistent snapshot: pg_dump runs in one transaction, so the app may keep running.
#
# Where to run it (HAMMURABI_PG_EXEC = the command that runs a program in the Postgres container; word-split on purpose):
#   compose (default project):  nothing to set; the default is `docker exec -i hammurabi-postgres`
#   compose, another project:   HAMMURABI_PG_EXEC='docker compose -p NAME exec -T postgres'
#   Kubernetes:                 HAMMURABI_PG_EXEC='kubectl -n hammurabi exec -i deploy/postgres --'
# Other knobs: HAMMURABI_DB_USER (default hammurabi), HAMMURABI_DB_NAME (default hammurabi).
# A variable that is SET BUT EMPTY is refused, never read as "unset" (a computed command that came out blank must not fall back to the
# local default container). The target command is printed on stderr before anything runs. (A deliberate departure from Adder's scripts.)
#
# This does NOT back up DATA_ENCRYPTION_KEY / JWT_SECRET: keep those separately (a restored database is useless
# for any encrypted column without its key). Runbook: .claude/docs/persistence.md "Backup and restore".
set -euo pipefail

db_user=${HAMMURABI_DB_USER:-hammurabi}
db_name=${HAMMURABI_DB_NAME:-hammurabi}
exec_cmd=${HAMMURABI_PG_EXEC-docker exec -i hammurabi-postgres}
if [ -z "${exec_cmd//[[:space:]]/}" ]; then
    echo "refusing: HAMMURABI_PG_EXEC is set but empty (unset it for the default compose container, or give the command)" >&2
    exit 2
fi
read -r -a pg_exec <<<"$exec_cmd"

usage="usage: $0 [OUTPUT.dump]   (see the header of this script)"
case "${1:-}" in
    -h | --help) echo "$usage"; exit 0 ;;
esac
if [ "$#" -gt 1 ]; then
    echo "$usage" >&2
    exit 2
fi
out=${1:-hammurabi-$(date -u +%Y%m%dT%H%M%SZ).dump}
case "$out" in -*) out=./$out ;; esac # an output name starting with `-` is a path, never an option of mktemp/ln/mv
if [ -e "$out" ]; then
    echo "refusing to overwrite existing $out" >&2
    exit 1
fi

echo "backing up '$db_name' via: $exec_cmd" >&2
umask 077
tmp=$(mktemp -- "${out}.partial.XXXXXX")
keep_tmp=0
trap '[ "$keep_tmp" -eq 1 ] || rm -f "$tmp"' EXIT

"${pg_exec[@]}" pg_dump -U "$db_user" -d "$db_name" -Fc >"$tmp"

# The archive must be a readable, non-empty pg_dump archive before it is called a backup.
entries=$("${pg_exec[@]}" pg_restore --list <"$tmp" | grep -c ' TABLE DATA ' || true)
if [ "$entries" -lt 1 ]; then
    echo "the dump lists no tables - not keeping it" >&2
    exit 1
fi

# `ln` fails if $out appeared meanwhile, so the "never overwrites" holds even against a concurrent run (mv would replace it). Where hard
# links are unsupported (exFAT/FAT, some SMB/NFS shares) `ln` fails for another reason: then refuse only if $out exists, else fall back to
# a no-clobber move and verify it landed. The temp file is deleted only once the dump is in place (every refusal after a complete dump keeps
# it and names it).
if ! ln -- "$tmp" "$out" 2>/dev/null; then
    if [ -e "$out" ]; then
        keep_tmp=1
        echo "refusing to overwrite $out; the complete dump is kept at $tmp" >&2
        exit 1
    fi
    mv -n -- "$tmp" "$out" || true
    if [ -e "$tmp" ] || [ ! -s "$out" ]; then
        keep_tmp=1
        echo "could not place the dump at $out; the complete dump is kept at $tmp" >&2
        exit 1
    fi
else
    rm -f -- "$tmp"
fi
trap - EXIT
echo "wrote $out ($(wc -c <"$out" | tr -d ' ') bytes, $entries tables)"
