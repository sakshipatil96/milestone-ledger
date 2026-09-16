#!/bin/sh
set -eu

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=app_password="$DB_APP_PASSWORD" <<-'EOSQL'
CREATE ROLE milestone_app LOGIN PASSWORD :'app_password';
GRANT CONNECT ON DATABASE milestone_ledger TO milestone_app;
GRANT USAGE ON SCHEMA public TO milestone_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE ON TABLES TO milestone_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO milestone_app;
EOSQL
