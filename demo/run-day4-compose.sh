#!/usr/bin/env bash
set -euo pipefail

command -v docker >/dev/null
command -v htpasswd >/dev/null
command -v openssl >/dev/null
command -v jq >/dev/null

# Generate disposable local credentials in the process environment; never write them to the repository.
export DB_OWNER_PASSWORD="$(openssl rand -hex 20)"
export DB_APP_PASSWORD="$(openssl rand -hex 20)"
export CERTIFIER_PASSWORD="$(openssl rand -hex 20)"
export ACCOUNTS_PASSWORD="$(openssl rand -hex 20)"
export MANAGER_PASSWORD="$(openssl rand -hex 20)"
export BANK_WEBHOOK_SIGNING_SECRET="$(openssl rand -hex 32)"
export APP_SETUP_PROJECT_ID=30000000-0000-0000-0000-000000000001
export BANK_WEBHOOK_PROJECT_ID="$APP_SETUP_PROJECT_ID"
export APP_SECURITY_CERTIFIER_PASSWORD_HASH="$(htpasswd -bnBC 10 '' "$CERTIFIER_PASSWORD" | tr -d ':\n')"
export APP_SECURITY_ACCOUNTS_PASSWORD_HASH="$(htpasswd -bnBC 10 '' "$ACCOUNTS_PASSWORD" | tr -d ':\n')"
export APP_SECURITY_MANAGER_PASSWORD_HASH="$(htpasswd -bnBC 10 '' "$MANAGER_PASSWORD" | tr -d ':\n')"

project_name="milestone_ledger_day4_$(date +%s)_$$"
compose=(docker compose -p "$project_name" -f compose.yaml -f demo/compose.day4.yaml)
cleanup() { "${compose[@]}" down >/dev/null; }
trap cleanup EXIT

"${compose[@]}" config --quiet
"${compose[@]}" up -d --build --wait
BASE_URL=http://localhost:18080 ./demo/day4-collections.sh
printf 'Isolated Compose project %s passed; containers stopped. Its named test volume remains available for inspection.\n' "$project_name"
