#!/usr/bin/env bash
set -euo pipefail

: "${ACCOUNTS_PASSWORD:?Set the synthetic accounts password externally}"
: "${MANAGER_PASSWORD:?Set the synthetic manager password externally}"
: "${BANK_WEBHOOK_SIGNING_SECRET:?Set the synthetic webhook secret externally}"
command -v jq >/dev/null

base_url="${BASE_URL:-http://localhost:8080}"
bank_url="${BANK_URL:-http://localhost:8089}"
project_id=30000000-0000-0000-0000-000000000001
unique="$(date +%s)-$$"
cookie_jar="$(mktemp)"
mapping_id=""
cleanup() {
  if [[ -n "$mapping_id" ]]; then curl -fsS -X DELETE "$bank_url/__admin/mappings/$mapping_id" >/dev/null || true; fi
  rm -f "$cookie_jar"
}
trap cleanup EXIT

csrf="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" -c "$cookie_jar" "$base_url/api/v1/csrf-token" | jq -r '.token')"
demand="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/demands?status=PARTIALLY_PAID" | jq -c '.items[0]')"
demand_id="$(printf '%s' "$demand" | jq -r '.id')"
reference="$(printf '%s' "$demand" | jq -r '.reference')"
test "$demand_id" != null
test "$reference" != null
bank_id="missed-day5-$unique"
as_of="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

deliver() {
  local bank="$1" event="$2" payload timestamp signature accepted id state
  payload="$(jq -nc --arg bank "$bank" --arg event "$event" --arg posted "$as_of" \
    '{eventId:$event,receipt:{bankReceiptId:$bank,amountPaise:"100000",currency:"INR",postedAt:$posted}}')"
  timestamp="$(date +%s)"
  signature="sha256=$(printf '%s.%s' "$timestamp" "$payload" | openssl dgst -sha256 -hmac "$BANK_WEBHOOK_SIGNING_SECRET" -hex | sed 's/^.* //')"
  accepted="$(curl -fsS -H 'Content-Type: application/json' -H "X-Bank-Timestamp: $timestamp" \
    -H "X-Bank-Signature: $signature" --data "$payload" "$base_url/api/v1/webhooks/bank")"
  id="$(printf '%s' "$accepted" | jq -r '.ingestionEventId')"
  for ((attempt=0; attempt<100; attempt++)); do
    state="$(curl -fsS --user "manager:$MANAGER_PASSWORD" "$base_url/api/v1/ingestion-events/$id" | jq -r '.status')"
    if [[ "$state" == PROCESSED ]]; then return; fi
    if [[ "$state" == FAILED || "$state" == CONFLICT ]]; then exit 1; fi
    sleep 0.1
  done
  printf 'Webhook delivery timed out\n' >&2
  exit 1
}

duplicate_bank="duplicate-day5-$unique"
equal_bank="equal-day5-$unique"
for number in 1 2 3 4 5; do deliver "$duplicate_bank" "duplicate-$unique-$number"; done
deliver "$equal_bank" "equal-$unique"
for identity in "$duplicate_bank" "$equal_bank"; do
  receipt_for_identity="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/receipts?limit=100" \
    | jq -r --arg bank "$identity" '[.items[] | select(.bankReceiptId == $bank and .amountPaise == "100000")][0].id')"
  test "$receipt_for_identity" != null
  curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/financial-entries?receiptId=$receipt_for_identity" \
    | jq -e '[.items[] | select(.kind == "RECEIPT")] | length == 1' >/dev/null
done
test "$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/receipts?limit=100" \
  | jq --arg first "$duplicate_bank" --arg second "$equal_bank" \
    '[.items[] | select(.bankReceiptId == $first or .bankReceiptId == $second)] | length')" -eq 2

stub() {
  local snapshot="$1" amount="$2" cursor="$3" items payload mapping
  if [[ -n "$mapping_id" ]]; then curl -fsS -X DELETE "$bank_url/__admin/mappings/$mapping_id" >/dev/null; fi
  mapping_id=""
  if [[ "$amount" == none ]]; then
    items='[]'
  else
    items="$(jq -nc --arg id "$bank_id" --arg amount "$amount" --arg ref "$reference" --arg posted "$as_of" \
      '[{bankReceiptId:$id,amountPaise:$amount,currency:"INR",demandReference:$ref,postedAt:$posted}]')"
  fi
  payload="$(jq -nc --arg snapshot "$snapshot" --arg asOf "$as_of" --argjson items "$items" \
    --arg cursor "$cursor" '{snapshotId:$snapshot,asOf:$asOf,items:$items,nextCursor:(if $cursor == "null" then null else $cursor end)}')"
  mapping="$(jq -nc --argjson response "$payload" \
    '{priority:1,request:{method:"GET",url:"/receipts"},
      response:{status:200,headers:{"Content-Type":"application/json"},jsonBody:$response}}')"
  mapping_id="$(curl -fsS -H 'Content-Type: application/json' --data "$mapping" \
    "$bank_url/__admin/mappings" | jq -r '.id')"
  test "$mapping_id" != null
}

start_run() {
  local key="$1"
  curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" -b "$cookie_jar" \
    -H "X-XSRF-TOKEN: $csrf" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" --data "{\"projectId\":\"$project_id\"}" \
    "$base_url/api/v1/reconciliation-runs" | jq -r '.runId'
}

wait_run() {
  local id="$1" expected="$2" result status
  for ((attempt=0; attempt<350; attempt++)); do
    result="$(curl -fsS --user "manager:$MANAGER_PASSWORD" "$base_url/api/v1/reconciliation-runs/$id")"
    status="$(printf '%s' "$result" | jq -r '.status')"
    if [[ "$status" == COMPLETED || "$status" == COMPLETED_WITH_ERRORS || "$status" == FAILED ]]; then
      test "$status" = "$expected"
      printf '%s' "$result"
      return
    fi
    sleep 0.1
  done
  printf 'Reconciliation run %s timed out\n' "$id" >&2
  exit 1
}

stub "day5-recovery-$unique" 6000000 null
run="$(start_run "day5-recovery-$unique")"
result="$(wait_run "$run" COMPLETED)"
printf '%s' "$result" | jq -e '.bankCount == 1 and .recoveredCount == 1 and .failedCount == 0' >/dev/null
receipt="$(curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/receipts?limit=100" \
  | jq -c --arg bank "$bank_id" '[.items[] | select(.bankReceiptId == $bank)][0]')"
receipt_id="$(printf '%s' "$receipt" | jq -r '.id')"
test "$receipt_id" != null
curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/demands/$demand_id" \
  | jq -e '.status == "SETTLED" and .outstandingPaise == "0"' >/dev/null
curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/exceptions?status=OPEN&limit=100" \
  | jq -e 'any(.items[]; .type == "LOCAL_RECEIPT_NOT_IN_BANK")' >/dev/null

stub "day5-repeat-$unique" 6000000 null
repeat="$(start_run "day5-repeat-$unique")"
wait_run "$repeat" COMPLETED | jq -e '.recoveredCount == 0' >/dev/null
curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/financial-entries?receiptId=$receipt_id" \
  | jq -e '[.items[] | select(.kind == "RECEIPT")] | length == 1' >/dev/null

stub "day5-changed-$unique" 6000001 null
changed="$(start_run "day5-changed-$unique")"
wait_run "$changed" COMPLETED | jq -e '.conflictCount >= 1' >/dev/null
curl -fsS --user "accounts:$ACCOUNTS_PASSWORD" "$base_url/api/v1/receipts/$receipt_id" \
  | jq -e '.amountPaise == "6000000"' >/dev/null

stub "day5-unavailable-$unique" none unavailable
failed="$(start_run "day5-unavailable-$unique")"
wait_run "$failed" FAILED | jq -e '.errorCode == "BANK_UNAVAILABLE" and .finishedAt != null' >/dev/null

printf 'Day 5 passed: demand=%s recoveredReceipt=%s recoveryRun=%s repeatRun=%s conflictRun=%s failedRun=%s.\n' \
  "$demand_id" "$receipt_id" "$run" "$repeat" "$changed" "$failed"
if [[ -n "${DEMO_RESULT_FILE:-}" ]]; then
  jq -nc --arg demand "$demand_id" --arg receipt "$receipt_id" --arg run "$run" \
    '{demandId:$demand,receiptId:$receipt,runId:$run}' > "$DEMO_RESULT_FILE"
fi
