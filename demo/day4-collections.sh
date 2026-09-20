#!/usr/bin/env bash
set -euo pipefail

: "${CERTIFIER_PASSWORD:?Set the synthetic certifier password externally}"
: "${ACCOUNTS_PASSWORD:?Set the synthetic accounts password externally}"
: "${MANAGER_PASSWORD:?Set the synthetic manager password externally}"
: "${BANK_WEBHOOK_SIGNING_SECRET:?Set the synthetic webhook secret externally}"
command -v jq >/dev/null

base_url="${BASE_URL:-http://localhost:8080}"
run_id="$(date +%s)-$$"
cookie_jar="$(mktemp)"
trap 'rm -f "$cookie_jar"' EXIT

csrf_json="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" -c "$cookie_jar" "$base_url/api/v1/csrf-token")"
csrf="$(printf '%s' "$csrf_json" | jq -r '.token')"
milestones="$(curl -fsS --user "certifier:$CERTIFIER_PASSWORD" "$base_url/api/v1/projects/30000000-0000-0000-0000-000000000001/milestones")"
milestone_id="$(printf '%s' "$milestones" | jq -r '[.items[] | select(.status == "SCHEDULED")][0].id')"
test "$milestone_id" != null

certifier_cookie="$(mktemp)"
trap 'rm -f "$cookie_jar" "$certifier_cookie"' EXIT
certifier_csrf="$(curl -fsS --user "certifier:$CERTIFIER_PASSWORD" -c "$certifier_cookie" "$base_url/api/v1/csrf-token" | jq -r '.token')"
certification_body="$(jq -nc --arg ref "CERT-DAY4-$run_id" '{approvedAmountPaise:"10000000",currency:"INR",certificationReference:$ref}')"
certification="$(curl -fsS --user "certifier:$CERTIFIER_PASSWORD" -b "$certifier_cookie" \
  -H "X-XSRF-TOKEN: $certifier_csrf" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: day4-cert-$run_id" --data "$certification_body" \
  "$base_url/api/v1/milestones/$milestone_id/certification")"
demand_id="$(printf '%s' "$certification" | jq -r '.demand.id')"

receipt_body="$(jq -nc --arg event "evt-day4-$run_id" --arg bank "bank-day4-$run_id" \
  '{eventId:$event,receipt:{bankReceiptId:$bank,amountPaise:"4000000",currency:"INR",postedAt:"2026-09-16T10:00:00Z"}}')"
bank_timestamp="$(date +%s)"
bank_signature="sha256=$(printf '%s.%s' "$bank_timestamp" "$receipt_body" | openssl dgst -sha256 -hmac "$BANK_WEBHOOK_SIGNING_SECRET" -hex | sed 's/^.* //')"
accepted="$(curl -fsS -H 'Content-Type: application/json' -H "X-Bank-Timestamp: $bank_timestamp" \
  -H "X-Bank-Signature: $bank_signature" --data "$receipt_body" "$base_url/api/v1/webhooks/bank")"
event_id="$(printf '%s' "$accepted" | jq -r '.ingestionEventId')"

for ((attempt=0; attempt<100; attempt++)); do
  event="$(curl -fsS --user "manager:$MANAGER_PASSWORD" "$base_url/api/v1/ingestion-events/$event_id")"
  event_status="$(printf '%s' "$event" | jq -r '.status')"
  if [[ "$event_status" == PROCESSED ]]; then break; fi
  if [[ "$event_status" == FAILED || "$event_status" == CONFLICT ]]; then
    printf 'Receipt processing ended with %s\n' "$event_status" >&2
    exit 1
  fi
  sleep 0.2
done
test "$event_status" = PROCESSED
receipt_id="$(printf '%s' "$event" | jq -r '.receiptId')"
exception_json="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/exceptions?status=OPEN")"
exception_id="$(printf '%s' "$exception_json" | jq -r --arg receipt "$receipt_id" '[.items[] | select(.receiptId == $receipt and .type == "UNALLOCATED_FUNDS")][0].id')"
test "$exception_id" != null

outstanding="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/demands?status=OPEN&status=PARTIALLY_PAID")"
received="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/receipts?status=UNALLOCATED")"
printf '%s' "$outstanding" | jq -e --arg id "$demand_id" 'any(.items[]; .id == $id) and (.ingestion.pendingCount >= 0)' >/dev/null
printf '%s' "$received" | jq -e --arg id "$receipt_id" 'any(.items[]; .id == $id)' >/dev/null

note_body='{"reason":"Synthetic remittance advice identifies this demand."}'
note="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" -b "$cookie_jar" \
  -H "X-XSRF-TOKEN: $csrf" -H 'Content-Type: application/json' -H "Idempotency-Key: day4-note-$run_id" \
  --data "$note_body" "$base_url/api/v1/exceptions/$exception_id/notes")"
printf '%s' "$note" | jq -e --arg id "$exception_id" '.exceptionId == $id' >/dev/null

allocate() {
  local amount="$1" key="$2" body
  body="$(jq -nc --arg demand "$demand_id" --arg amount "$amount" \
    '{demandId:$demand,amountPaise:$amount,reason:"Synthetic remittance advice identifies this demand."}')"
  curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" -b "$cookie_jar" \
    -H "X-XSRF-TOKEN: $csrf" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    --data "$body" "$base_url/api/v1/receipts/$receipt_id/allocations"
}

partial="$(allocate 1500000 "day4-partial-$run_id")"
printf '%s' "$partial" | jq -e '.receipt.unallocatedPaise == "2500000" and .exceptionStatus == "OPEN"' >/dev/null
replayed="$(allocate 1500000 "day4-partial-$run_id")"
test "$replayed" = "$partial"
final="$(allocate 2500000 "day4-final-$run_id")"
printf '%s' "$final" | jq -e '.receipt.unallocatedPaise == "0" and .demand.outstandingPaise == "6000000" and .exceptionStatus == "RESOLVED"' >/dev/null
history="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/financial-entries?receiptId=$receipt_id")"
printf '%s' "$history" | jq -e '[.items[] | select(.kind == "ALLOCATION")] | length == 2' >/dev/null

printf 'Day 4 passed: demand=%s receipt=%s exception=%s; note, partial/full allocation, replay, and history verified.\n' \
  "$demand_id" "$receipt_id" "$exception_id"
