# API Contract

## Conventions and auth rules

- Base path `/api/v1`; JSON; UUID resource IDs; UTC ISO-8601 timestamps. The Day 1 routes below are the only implemented business routes.
- Amounts are decimal-string paise: `"4000000"` means ₹40,000. Currency is always `INR`. Reject floats, negative/zero money inputs, overflow, and unknown fields.
- Local prototype uses HTTP Basic with seeded identities and password hashes configured outside source control. Bind to localhost; require TLS if exposed. No signup/login API.
- `CERTIFIER`: read worklists and certify. `ACCOUNTS`: read, allocate, add exception notes, start reconciliation. `MANAGER`: accounts permissions plus reversals and failed-event retry. Manager does not implicitly gain certification permission.
- Webhooks use HMAC authentication only; bank source/project/account mapping comes from configuration. Health endpoints expose no financial data.
- All authenticated POSTs require `Idempotency-Key`. Same actor/operation/key and canonical request returns the original status/body; changed request returns `409`. Concurrent key reuse is serialized in the database. Validation failures are not cached.
- Collection responses use `{ "items": [...], "nextCursor": null }`; default limit 50, maximum 100. Opaque cursor uses a stable timestamp/ID order. IDs outside the configured project return `404`.
- Balances and related totals in a response come from one database snapshot. Receipt ingestion can lag acceptance; event status is explicit.

## Endpoints

| Method and path | Access | Result |
| --- | --- | --- |
| `GET /api/v1/projects` | All roles | Configured project/client metadata. |
| `GET /api/v1/projects/{projectId}/milestones` | All roles | Fixed milestone schedule and derived certification status. |
| `POST /milestones/{milestoneId}/certification` | Certifier | `201`, certification and demand. |
| `GET /demands?projectId=...&status=OPEN` | All roles | Demands with amount, received, outstanding, derived status. Optional status filter. |
| `GET /demands/{demandId}` | All roles | Demand details and balances. |
| `GET /receipts?projectId=...&status=UNALLOCATED` | All roles | Receipts with allocated/unallocated balances. Optional status filter. |
| `GET /receipts/{receiptId}` | All roles | Immutable receipt facts and balances. |
| `GET /financial-entries?receiptId=...` | All roles | Append-only history; require exactly one of receiptId/demandId. |
| `POST /receipts/{receiptId}/allocations` | Accounts, manager | `201`, allocation and updated balances. |
| `POST /allocations/{allocationId}/reversal` | Manager | `201`, full reversal and updated balances; seven-day scope. |
| `GET /exceptions?projectId=...&status=OPEN` | All roles | Reason, residual amount where applicable, and permitted resolution actions. |
| `POST /exceptions/{exceptionId}/notes` | Accounts, manager | `201`, append-only investigation note; does not close case. |
| `POST /reconciliation-runs` | Accounts, manager | `202`, queued run and Location header. |
| `GET /reconciliation-runs/{runId}` | All roles | Status, counts, error, and related exception IDs. Counts are provisional until terminal. |
| `POST /webhooks/bank` | Bank HMAC | `202`, durable inbox event acknowledgment. |
| `GET /ingestion-events?status=FAILED` | Manager | Minimal operational metadata; optional status filter. |
| `GET /ingestion-events/{eventId}` | Manager | Ingestion status, receipt ID, or failure/conflict code. |
| `POST /ingestion-events/{eventId}/retry` | Manager | `202`, reset a failed event to pending. |
| `GET /api/v1/health/live`, `GET /api/v1/health/ready` | Public, local | Minimal `UP`/`DOWN`; readiness checks database connectivity. |

References to event IDs in URL paths mean local inbox UUIDs; webhook `eventId` is the bank's delivery identifier.

## Request and response examples

### Inspect fixed setup

`GET /api/v1/projects?limit=50` (any configured role) returns:

```json
{"items":[{"id":"30000000-0000-0000-0000-000000000001","code":"DEMO-RIVER-001","name":"River Link Upgrade — Synthetic Demo","currency":"INR","client":{"id":"20000000-0000-0000-0000-000000000001","name":"Northstar Public Works Authority"}}],"nextCursor":null}
```

`GET /api/v1/projects/{projectId}/milestones` returns ordered schedule items:

```json
{"items":[{"id":"40000000-0000-0000-0000-000000000001","projectId":"30000000-0000-0000-0000-000000000001","sequence":1,"name":"Foundation package complete","status":"SCHEDULED","certifiedAmountPaise":null,"certificationReference":null,"certifiedAt":null,"certifiedBy":null}],"nextCursor":null}
```

Collections use `limit` default 50/max 100 and an opaque timestamp/ID cursor. Invalid UUIDs, limits, and cursors return `400 VALIDATION_ERROR`; unknown or out-of-scope projects return `404 NOT_FOUND`. Certification fields remain nullable until ML-03.

### Record certification

`POST /milestones/{milestoneId}/certification`, `Idempotency-Key: cert-m1`

```json
{"approvedAmountPaise":"10000000","currency":"INR","certificationReference":"CERT-DEMO-001","dueDate":"2026-09-30"}
```

`201 Created`

```json
{"milestoneId":"<milestoneId>","status":"CERTIFIED","demand":{"id":"<demandId>","reference":"DEM-DEMO-001","amountPaise":"10000000","allocatedPaise":"0","outstandingPaise":"10000000","currency":"INR","status":"OPEN"}}
```

`dueDate` is optional. Demand reference is generated by the server and is stable. A second certification under a different key returns `409 MILESTONE_ALREADY_CERTIFIED`.

### Accept a bank receipt

`POST /webhooks/bank`

Headers: `X-Bank-Timestamp: <unix-seconds>` and `X-Bank-Signature: sha256=<hex-HMAC>`.
Signature input is UTF-8 timestamp, a literal `.`, and the exact raw request body; use HMAC-SHA256 and constant-time comparison. The source is fixed by the configured signing secret.

```json
{"eventId":"evt-001","receipt":{"bankReceiptId":"bank-r001","amountPaise":"4000000","currency":"INR","demandReference":"DEM-DEMO-001","postedAt":"2026-09-16T10:00:00Z"}}
```

`202 Accepted`

```json
{"ingestionEventId":"<eventId>","status":"PENDING","duplicate":false}
```

An identical event replay returns `202`, the same inbox ID, its current status, and `duplicate:true`. A new event ID carrying the same receipt may initially return pending but must not create new money. Changed fields under the same event ID return `409 EVENT_ID_CONFLICT`; changed fields under an existing bank receipt ID are accepted durably, then become `CONFLICT` with an exception.

### Read outstanding demand after processing

`GET /demands/{demandId}` → `200 OK`

```json
{"id":"<demandId>","reference":"DEM-DEMO-001","amountPaise":"10000000","allocatedPaise":"4000000","outstandingPaise":"6000000","currency":"INR","status":"PARTIALLY_PAID"}
```

### Resolve an unmatched receipt

`GET /exceptions?projectId=<projectId>&status=OPEN` → `200 OK`

```json
{"items":[{"id":"<exceptionId>","type":"UNALLOCATED_FUNDS","receiptId":"<receiptId>","reasonCode":"MISSING_REFERENCE","unallocatedPaise":"2000000","status":"OPEN","actions":["ALLOCATE","ADD_NOTE"]}],"nextCursor":null}
```

`POST /receipts/{receiptId}/allocations`, `Idempotency-Key: match-r2`

```json
{"demandId":"<demandId>","amountPaise":"2000000","reason":"Synthetic remittance advice confirms Milestone 1."}
```

`201 Created`

```json
{"allocationId":"<allocationId>","receiptId":"<receiptId>","demandId":"<demandId>","amountPaise":"2000000","receiptUnallocatedPaise":"0","demandOutstandingPaise":"4000000"}
```

The residual-funds exception resolves automatically only when unallocated balance reaches zero. Manual requests exceeding either balance fail entirely; automatic matching may allocate a smaller amount and leave excess unallocated.

### Reverse a mistaken allocation

`POST /allocations/{allocationId}/reversal`, `Idempotency-Key: reverse-a1`

```json
{"reason":"Remittance advice identifies a different milestone."}
```

`201 Created`

```json
{"reversalId":"<reversalId>","reversesAllocationId":"<allocationId>","amountPaise":"-2000000","receiptUnallocatedPaise":"2000000","demandOutstandingPaise":"6000000"}
```

Only a full reversal is supported. Reallocation requires a separate allocation request. `POST /exceptions/{exceptionId}/notes` accepts `{"reason":"Awaiting remittance advice."}` and returns `201` with `noteId`, `exceptionId`, `actorId`, `reason`, and `createdAt`.

### Recover missing notifications

`POST /reconciliation-runs`, `Idempotency-Key: recon-demo-1`

```json
{"projectId":"<projectId>"}
```

`202 Accepted`, `Location: /api/v1/reconciliation-runs/<runId>`

```json
{"id":"<runId>","status":"QUEUED"}
```

`GET /reconciliation-runs/{runId}` after processing → `200 OK`

```json
{"id":"<runId>","status":"COMPLETED","asOf":"2026-09-16T11:00:00Z","bankCount":3,"recoveredCount":1,"conflictCount":0,"failedCount":0,"exceptionIds":[],"errorCode":null}
```

Use a new key for a new run. `recoveredCount` counts new receipts created by this run, not duplicate events; existing open matching exceptions remain visible in the worklist even when reconciliation completes successfully.

`POST /ingestion-events/{eventId}/retry` accepts `{"reason":"Database recovered."}` plus an idempotency key and returns `202 {"ingestionEventId":"<eventId>","status":"PENDING"}`. Only failed rows are retryable.

## Error cases

All errors use the same envelope and never echo sensitive payloads:

```json
{"error":{"code":"ALLOCATION_EXCEEDS_OUTSTANDING","message":"Requested allocation exceeds the demand's outstanding amount.","requestId":"<requestId>"}}
```

| HTTP | Codes / meaning |
| --- | --- |
| `400` | `VALIDATION_ERROR`, `IDEMPOTENCY_KEY_REQUIRED`: malformed JSON, invalid amount/reference/filter/cursor. |
| `401` | `UNAUTHENTICATED`, `INVALID_SIGNATURE`, `STALE_SIGNATURE`. |
| `403` | `FORBIDDEN`: authenticated identity lacks the required role. |
| `404` | `NOT_FOUND`: missing or inaccessible resource. |
| `409` | `IDEMPOTENCY_KEY_REUSED`, `EVENT_ID_CONFLICT`, `MILESTONE_ALREADY_CERTIFIED`, `RECONCILIATION_ALREADY_RUNNING`, `ALREADY_REVERSED`, `EVENT_NOT_RETRYABLE`. |
| `409` | `ALLOCATION_EXCEEDS_UNALLOCATED`, `ALLOCATION_EXCEEDS_OUTSTANDING`: re-read current balances before deciding on a new request. |
| `422` | `PROJECT_MISMATCH`, `UNSUPPORTED_CURRENCY`: valid structure violates business scope. |
| `503` | `DEPENDENCY_UNAVAILABLE`: work was not accepted; retry with the same key/event identity. |

The simulator's receipt-list endpoint and fault controls are development fixtures, not public product APIs. Provide a prepared API collection and fixture script that prove duplicates, missing-event recovery, and concurrent allocation safety.
