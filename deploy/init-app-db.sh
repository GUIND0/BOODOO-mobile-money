#!/usr/bin/env bash
set -euo pipefail
: "${APP_DB_USER:?}" "${APP_DB_PASSWORD:?}" "${POSTGRES_DB:?}"
psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 --set=app_user="$APP_DB_USER" --set=app_password="$APP_DB_PASSWORD" --set=app_database="$POSTGRES_DB" <<'SQL'
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
ALTER DATABASE :"app_database" OWNER TO :"app_user";
ALTER SCHEMA public OWNER TO :"app_user";
SQL
