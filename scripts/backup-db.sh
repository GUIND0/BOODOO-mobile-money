#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${PGDATABASE:?Set the source database explicitly}"
: "${PGHOST:?Set PGHOST}"
: "${PGUSER:?Set PGUSER}"
output=${1:?Usage: backup-db.sh /secure/path/backup.dump}
if [[ -e "$output" || -e "$output.sha256" ]]; then echo 'Refusing to overwrite an existing backup' >&2; exit 1; fi
partial="$output.partial"
trap 'rm -f -- "$partial"' EXIT
pg_dump --format=custom --no-owner --no-acl --file="$partial"
pg_restore --list "$partial" >/dev/null
mv -- "$partial" "$output"
if command -v sha256sum >/dev/null; then sha256sum "$output" | cut -d' ' -f1 > "$output.sha256"; else shasum -a 256 "$output" | cut -d' ' -f1 > "$output.sha256"; fi
