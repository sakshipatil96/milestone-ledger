#!/usr/bin/env bash
set -euo pipefail

for command_name in docker htpasswd openssl jq curl; do command -v "$command_name" >/dev/null; done
export DB_OWNER_PASSWORD="$(openssl rand -hex 20)"
export DB_APP_PASSWORD="$(openssl rand -hex 20)"
export CERTIFIER_PASSWORD="$(openssl rand -hex 20)"
export ACCOUNTS_PASSWORD="$(openssl rand -hex 20)"
export MANAGER_PASSWORD="$(openssl rand -hex 20)"
export BANK_WEBHOOK_SIGNING_SECRET="$(openssl rand -hex 32)"
export APP_SETUP_PROJECT_ID=30000000-0000-0000-0000-000000000001
export BANK_WEBHOOK_PROJECT_ID="$APP_SETUP_PROJECT_ID"
export APP_VERSION="$(git rev-parse --short HEAD)-working-tree"
export APP_SECURITY_CERTIFIER_PASSWORD_HASH="$(htpasswd -bnBC 10 '' "$CERTIFIER_PASSWORD" | tr -d ':\n')"
export APP_SECURITY_ACCOUNTS_PASSWORD_HASH="$(htpasswd -bnBC 10 '' "$ACCOUNTS_PASSWORD" | tr -d ':\n')"
export APP_SECURITY_MANAGER_PASSWORD_HASH="$(htpasswd -bnBC 10 '' "$MANAGER_PASSWORD" | tr -d ':\n')"

project_name="milestone_ledger_day5_$(date +%s)_$$"
compose=(docker compose -p "$project_name" -f compose.yaml -f demo/compose.day4.yaml)
result_file="$(mktemp)"
cleanup() { "${compose[@]}" down >/dev/null; rm -f "$result_file"; }
trap cleanup EXIT

"${compose[@]}" config --quiet
"${compose[@]}" up -d --build --wait
started="$(date +%s)"
BASE_URL=http://localhost:18080 ./demo/day4-collections.sh
BASE_URL=http://localhost:18080 BANK_URL=http://localhost:18089 DEMO_RESULT_FILE="$result_file" ./demo/day5-reconciliation.sh
elapsed="$(( $(date +%s) - started ))"
test "$elapsed" -le 300

"${compose[@]}" restart app >/dev/null
"${compose[@]}" up -d --wait >/dev/null
run_id="$(jq -r '.runId' "$result_file")"
receipt_id="$(jq -r '.receiptId' "$result_file")"
curl -fsS --user "manager:$MANAGER_PASSWORD" "http://localhost:18080/api/v1/reconciliation-runs/$run_id" \
  | jq -e '.status == "COMPLETED" and .recoveredCount == 1' >/dev/null
curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "http://localhost:18080/api/v1/receipts/$receipt_id" \
  | jq -e '.amountPaise == "6000000"' >/dev/null
printf 'Five-minute proof passed in %ss, excluding build/startup; persisted run and receipt survived app restart.\n' "$elapsed"
