#!/usr/bin/env bash
set -euo pipefail

: "${CERTIFIER_PASSWORD:?Set the raw demo password outside this script}"
: "${ACCOUNTS_PASSWORD:?Set the raw demo password outside this script}"
: "${MANAGER_PASSWORD:?Set the raw demo password outside this script}"
: "${BANK_WEBHOOK_SIGNING_SECRET:?Set the local webhook signing secret outside this script}"

base_url="${BASE_URL:-http://localhost:8080}"

ready="$(curl --fail --silent --show-error "$base_url/api/v1/health/ready")"
printf 'readiness: %s\n' "$ready"

for role in certifier accounts manager; do
  case "$role" in
    certifier) password_var=CERTIFIER_PASSWORD ;;
    accounts) password_var=ACCOUNTS_PASSWORD ;;
    manager) password_var=MANAGER_PASSWORD ;;
  esac
  password="${!password_var}"
  projects="$(curl --fail --silent --show-error --user "$role:$password" "$base_url/api/v1/projects")"
  project_id="$(printf '%s' "$projects" | cut -d '"' -f 6)"
  test -n "$project_id"
  milestones="$(curl --fail --silent --show-error --user "$role:$password" "$base_url/api/v1/projects/$project_id/milestones")"
  printf '%s projects: %s\n' "$role" "$projects"
  printf '%s milestones: %s\n' "$role" "$milestones"
done

test "$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/api/v1/projects")" = 401
test "$(curl --silent --output /dev/null --write-out '%{http_code}' --user 'certifier:invalid' "$base_url/api/v1/projects")" = 401
printf '%s\n' 'missing and invalid credentials correctly returned 401'

cookie_jar="$(mktemp)"
trap 'rm -f "$cookie_jar"' EXIT
csrf_response="$(curl --fail --silent --show-error --user "certifier:$CERTIFIER_PASSWORD" -c "$cookie_jar" "$base_url/api/v1/csrf-token")"
csrf_token="$(printf '%s' "$csrf_response" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
test -n "$csrf_token"

milestones="$(curl --fail --silent --show-error --user "certifier:$CERTIFIER_PASSWORD" "$base_url/api/v1/projects/30000000-0000-0000-0000-000000000001/milestones")"
scheduled="$(printf '%s' "$milestones" | sed 's/},{"id"/\n{"id"/g' | grep '"status":"SCHEDULED"' | head -n 1)"
milestone_id="$(printf '%s' "$scheduled" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
test -n "$milestone_id"

certification_body='{"approvedAmountPaise":"10000000","currency":"INR","certificationReference":"CERT-DEMO-001","dueDate":"2026-09-30"}'
certification="$(curl --fail --silent --show-error --user "certifier:$CERTIFIER_PASSWORD" -b "$cookie_jar" \
  -H "X-XSRF-TOKEN: $csrf_token" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-certification-001' \
  --data "$certification_body" "$base_url/api/v1/milestones/$milestone_id/certification")"
printf '%s\n' "certification: $certification"
printf '%s' "$certification" | grep -q '"status":"CERTIFIED"'
printf '%s' "$certification" | grep -q '"allocatedPaise":"0"'
printf '%s' "$certification" | grep -q '"outstandingPaise":"10000000"'
demand_id="$(printf '%s' "$certification" | sed -n 's/.*"demand":{"id":"\([^"]*\)".*/\1/p')"
test -n "$demand_id"

demand="$(curl --fail --silent --show-error --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/demands/$demand_id")"
printf '%s\n' "demand: $demand"
printf '%s' "$demand" | grep -q '"status":"OPEN"'

replay="$(curl --fail --silent --show-error --user "certifier:$CERTIFIER_PASSWORD" -b "$cookie_jar" \
  -H "X-XSRF-TOKEN: $csrf_token" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-certification-001' \
  --data "$certification_body" "$base_url/api/v1/milestones/$milestone_id/certification")"
test "$replay" = "$certification"
test "$(curl --silent --output /dev/null --write-out '%{http_code}' --user "certifier:$CERTIFIER_PASSWORD" -b "$cookie_jar" \
  -H "X-XSRF-TOKEN: $csrf_token" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-certification-001' \
  --data '{"approvedAmountPaise":"10000001","currency":"INR","certificationReference":"CERT-DEMO-001","dueDate":"2026-09-30"}' \
  "$base_url/api/v1/milestones/$milestone_id/certification")" = 409
test "$(curl --silent --output /dev/null --write-out '%{http_code}' --user "certifier:$CERTIFIER_PASSWORD" -b "$cookie_jar" \
  -H "X-XSRF-TOKEN: $csrf_token" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-certification-002' \
  --data "$certification_body" "$base_url/api/v1/milestones/$milestone_id/certification")" = 409
accounts_cookie="$(mktemp)"
manager_cookie="$(mktemp)"
trap 'rm -f "$cookie_jar" "$accounts_cookie" "$manager_cookie"' EXIT
accounts_csrf="$(curl --fail --silent --show-error --user "accounts:$ACCOUNTS_PASSWORD" -c "$accounts_cookie" "$base_url/api/v1/csrf-token" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
manager_csrf="$(curl --fail --silent --show-error --user "manager:$MANAGER_PASSWORD" -c "$manager_cookie" "$base_url/api/v1/csrf-token" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
test -n "$accounts_csrf"
test -n "$manager_csrf"
test "$(curl --silent --output /dev/null --write-out '%{http_code}' --user "accounts:$ACCOUNTS_PASSWORD" -b "$accounts_cookie" \
  -H "X-XSRF-TOKEN: $accounts_csrf" -H 'Content-Type: application/json' -H 'Idempotency-Key: accounts-cannot-certify' \
  --data "$certification_body" "$base_url/api/v1/milestones/$milestone_id/certification")" = 403
test "$(curl --silent --output /dev/null --write-out '%{http_code}' --user "manager:$MANAGER_PASSWORD" -b "$manager_cookie" \
  -H "X-XSRF-TOKEN: $manager_csrf" -H 'Content-Type: application/json' -H 'Idempotency-Key: manager-cannot-certify' \
  --data "$certification_body" "$base_url/api/v1/milestones/$milestone_id/certification")" = 403
printf '%s\n' 'certification replay, changed-key reuse, duplicate certification, and manager denial verified'

bank_timestamp="$(date +%s)"
receipt_body='{"eventId":"evt-demo-001","receipt":{"bankReceiptId":"bank-demo-001","amountPaise":"4000000","currency":"INR","demandReference":"DEM-UNKNOWN","postedAt":"2026-09-16T10:00:00Z"}}'
bank_signature="sha256=$(printf '%s.%s' "$bank_timestamp" "$receipt_body" | openssl dgst -sha256 -hmac "$BANK_WEBHOOK_SIGNING_SECRET" -hex | sed 's/^.* //')"
accepted="$(curl --fail --silent --show-error -H 'Content-Type: application/json' -H "X-Bank-Timestamp: $bank_timestamp" \
  -H "X-Bank-Signature: $bank_signature" --data "$receipt_body" "$base_url/api/v1/webhooks/bank")"
printf '%s\n' "accepted bank event: $accepted"
printf '%s' "$accepted" | grep -q '"status":"PENDING"'
ingestion_event_id="$(printf '%s' "$accepted" | sed -n 's/.*"ingestionEventId":"\([^"]*\)".*/\1/p')"
test -n "$ingestion_event_id"
event="$(curl --fail --silent --show-error --user "manager:$MANAGER_PASSWORD" "$base_url/api/v1/ingestion-events/$ingestion_event_id")"
printf '%s\n' "pending bank event: $event"
printf '%s' "$event" | grep -q '"status":"PENDING"'
printf '%s\n' 'receipt processing is intentionally not implemented: accepted events remain PENDING.'
