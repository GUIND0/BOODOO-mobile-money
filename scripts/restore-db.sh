#!/usr/bin/env bash
set -euo pipefail
: "${PGDATABASE:?Set a new empty target database explicitly}"
: "${PGHOST:?Set PGHOST}"
: "${PGUSER:?Set PGUSER}"
input=${1:?Usage: restore-db.sh /secure/path/backup.dump}
: "${RESTORE_TO_EMPTY_DATABASE:?Set RESTORE_TO_EMPTY_DATABASE=yes for this isolated restore}"
[[ "$RESTORE_TO_EMPTY_DATABASE" == yes ]] || exit 1
expected=$(cat "$input.sha256")
if command -v sha256sum >/dev/null; then actual=$(sha256sum "$input" | cut -d' ' -f1); else actual=$(shasum -a 256 "$input" | cut -d' ' -f1); fi
[[ "$expected" =~ ^[a-f0-9]{64}$ && "$actual" == "$expected" ]] || { echo 'Backup checksum mismatch' >&2; exit 1; }
count=$(psql -X -A -t --set=ON_ERROR_STOP=1 --command="SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')")
[[ "$count" == 0 ]] || { echo 'Restore target is not empty; refusing' >&2; exit 1; }
pg_restore --exit-on-error --single-transaction --no-owner --no-acl --dbname="$PGDATABASE" "$input"
