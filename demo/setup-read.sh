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

command -v jq >/dev/null
run_id="$(date +%s)-$$"

send_receipt() {
  local delivery_id="$1" bank_id="$2" amount="$3" reference="$4"
  local receipt_body bank_timestamp bank_signature
  receipt_body="$(jq -nc --arg event "$delivery_id" --arg bank "$bank_id" --arg amount "$amount" \
    --arg reference "$reference" '{eventId:$event,receipt:({bankReceiptId:$bank,amountPaise:$amount,currency:"INR",postedAt:"2026-09-16T10:00:00Z"} + (if $reference == "__OMIT__" then {} else {demandReference:$reference} end))}')"
  bank_timestamp="$(date +%s)"
  bank_signature="sha256=$(printf '%s.%s' "$bank_timestamp" "$receipt_body" | openssl dgst -sha256 -hmac "$BANK_WEBHOOK_SIGNING_SECRET" -hex | sed 's/^.* //')"
  curl --fail --silent --show-error -H 'Content-Type: application/json' -H "X-Bank-Timestamp: $bank_timestamp" \
    -H "X-Bank-Signature: $bank_signature" --data "$receipt_body" "$base_url/api/v1/webhooks/bank"
}

wait_event() {
  local event_id="$1" expected="$2" event status
  for ((attempt=0; attempt<100; attempt++)); do
    event="$(curl --fail --silent --show-error --user "manager:$MANAGER_PASSWORD" "$base_url/api/v1/ingestion-events/$event_id")"
    status="$(jq -r '.status' <<<"$event")"
    if [[ "$status" == "$expected" ]]; then
      printf '%s' "$event"
      return 0
    fi
    if [[ "$status" == FAILED || "$status" == CONFLICT || "$status" == PROCESSED ]]; then
      printf 'unexpected terminal event status: %s\n' "$status" >&2
      return 1
    fi
    sleep 0.2
  done
  printf 'timed out waiting for event %s\n' "$event_id" >&2
  return 1
}

read_demand() {
  curl --fail --silent --show-error --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/demands/$demand_id"
}

partial_bank="bank-demo-partial-$run_id"
partial_delivery="evt-demo-partial-$run_id"
partial_accepted="$(send_receipt "$partial_delivery" "$partial_bank" 4000000 "$(jq -r '.demand.reference' <<<"$certification")")"
partial_event="$(wait_event "$(jq -r '.ingestionEventId' <<<"$partial_accepted")" PROCESSED)"
partial_receipt="$(jq -r '.receiptId' <<<"$partial_event")"
jq -e '.allocatedPaise == "4000000" and .outstandingPaise == "6000000"' <<<"$(read_demand)" >/dev/null

# Five deliveries of the same bank receipt: a replayed delivery ID and three new IDs.
replayed="$(send_receipt "$partial_delivery" "$partial_bank" 4000000 "$(jq -r '.demand.reference' <<<"$certification")")"
jq -e --arg id "$partial_receipt" '.duplicate == true' <<<"$replayed" >/dev/null
for number in 2 3 4; do
  duplicate="$(send_receipt "evt-demo-duplicate-$number-$run_id" "$partial_bank" 4000000 "$(jq -r '.demand.reference' <<<"$certification")")"
  duplicate_event="$(wait_event "$(jq -r '.ingestionEventId' <<<"$duplicate")" PROCESSED)"
  jq -e --arg id "$partial_receipt" '.receiptId == $id' <<<"$duplicate_event" >/dev/null
done
partial_history="$(curl --fail --silent --show-error --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/financial-entries?receiptId=$partial_receipt")"
jq -e '[.items[].kind] | sort == ["ALLOCATION","RECEIPT"]' <<<"$partial_history" >/dev/null
jq -e '.allocatedPaise == "4000000" and .outstandingPaise == "6000000"' <<<"$(read_demand)" >/dev/null

# The final receipt settles the demand and makes the extra ₹10,000 explicit.
final_accepted="$(send_receipt "evt-demo-final-$run_id" "bank-demo-final-$run_id" 7000000 "$(jq -r '.demand.reference' <<<"$certification")")"
final_event="$(wait_event "$(jq -r '.ingestionEventId' <<<"$final_accepted")" PROCESSED)"
final_receipt="$(jq -r '.receiptId' <<<"$final_event")"
jq -e '.allocatedPaise == "10000000" and .outstandingPaise == "0" and .status == "SETTLED"' <<<"$(read_demand)" >/dev/null
jq -e '.allocatedPaise == "6000000" and .unallocatedPaise == "1000000"' <<<"$(curl --fail --silent --show-error --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/receipts/$final_receipt")" >/dev/null

for number in 1 2; do
  equal_accepted="$(send_receipt "evt-demo-equal-$number-$run_id" "bank-demo-equal-$number-$run_id" 2000000 __OMIT__)"
  equal_event="$(wait_event "$(jq -r '.ingestionEventId' <<<"$equal_accepted")" PROCESSED)"
  equal_receipt="$(jq -r '.receiptId' <<<"$equal_event")"
  jq -e '.amountPaise == "2000000" and .unallocatedPaise == "2000000"' \
    <<<"$(curl --fail --silent --show-error --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/receipts/$equal_receipt")" >/dev/null
  if [[ "$number" == 1 ]]; then equal_first="$equal_receipt"; else equal_second="$equal_receipt"; fi
done
test "$equal_first" != "$equal_second"
unknown_accepted="$(send_receipt "evt-demo-unknown-$run_id" "bank-demo-unknown-$run_id" 1000000 DEM-UNKNOWN)"
wait_event "$(jq -r '.ingestionEventId' <<<"$unknown_accepted")" PROCESSED >/dev/null

conflict_accepted="$(send_receipt "evt-demo-conflict-$run_id" "$partial_bank" 4000001 "$(jq -r '.demand.reference' <<<"$certification")")"
conflict_event="$(wait_event "$(jq -r '.ingestionEventId' <<<"$conflict_accepted")" CONFLICT)"
jq -e --arg id "$partial_receipt" '.receiptId == $id' <<<"$conflict_event" >/dev/null
jq -e '.allocatedPaise == "10000000" and .outstandingPaise == "0"' <<<"$(read_demand)" >/dev/null

exceptions="$(curl --fail --silent --show-error --user "manager:$MANAGER_PASSWORD" "$base_url/api/v1/exceptions?status=OPEN")"
jq -e '[.items[].reasonCode] | index("EXCESS_PAYMENT") != null and index("MISSING_REFERENCE") != null and index("UNKNOWN_REFERENCE") != null and index("FACTS_CHANGED") != null' <<<"$exceptions" >/dev/null
jq -e --arg id "$final_receipt" '[.items[] | select(.receiptId == $id and .reasonCode == "EXCESS_PAYMENT" and .residualAmountPaise == "1000000")] | length == 1' <<<"$exceptions" >/dev/null
printf '%s\n' 'Day 3 receipt, duplicate, balance, residual, and conflict checks passed.'
