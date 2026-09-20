# API Contract

## Day 3 implemented surface

All paths are under `/api/v1`, use JSON and UUID resource IDs, and use UTC ISO-8601 timestamps. Monetary values are decimal-string paise. The API currently supports INR only. Unknown JSON fields and numeric/fractional money values are rejected.

Local demo users authenticate with HTTP Basic using externally configured BCrypt password hashes. `CERTIFIER`, `ACCOUNTS`, and `MANAGER` may read project, milestone, and demand data. Only `CERTIFIER` may certify; `MANAGER` does not inherit that permission. Bank webhooks authenticate independently with HMAC and do not use Basic authentication.

| Method and path | Access | Result |
| --- | --- | --- |
| `GET /projects` | All three roles | Configured project/client metadata. |
| `GET /projects/{projectId}/milestones` | All three roles | Fixed schedule; status becomes `CERTIFIED` after certification. |
| `GET /csrf-token` | Authenticated | Returns a CSRF token and sets the `XSRF-TOKEN` cookie. |
| `POST /milestones/{milestoneId}/certification` | Certifier + CSRF | Creates certification and exactly one demand atomically. |
| `GET /demands/{demandId}` | All three roles | Demand facts plus current ledger-derived allocation, outstanding amount, and status. |
| `POST /webhooks/bank` | HMAC | Commits a normalized inbox event and returns it as `PENDING`. |
| `GET /ingestion-events/{eventId}`, `GET /ingestion-events` | Manager | Inspect an event or a bounded status-filtered operational list. |
| `POST /ingestion-events/{eventId}/retry` | Manager + CSRF | Reset one failed event with a reason and an idempotency key. |
| `GET /receipts/{receiptId}` | All three roles | Immutable receipt facts and ledger-derived allocated/unallocated balances. |
| `GET /financial-entries?receiptId=...` or `?demandId=...` | All three roles | Immutable history; exactly one filter is required. |
| `GET /exceptions` | All three roles | Scoped visible residual-fund and receipt-conflict cases. |

Automatic receipt processing is implemented. Reconciliation, manual allocation/reversal, notes, and collections worklists remain later scope.

## CSRF workflow

Basic-auth mutations require a CSRF token. First call `GET /csrf-token` with the same Basic identity, retain the `XSRF-TOKEN` cookie, then send the returned `token` value in the returned `headerName` (currently `X-XSRF-TOKEN`) on the mutation. The only CSRF exemption is `POST /webhooks/bank`, which is protected by HMAC.

## Certification

`POST /milestones/{milestoneId}/certification` requires a nonblank, trimmed `Idempotency-Key` of at most 200 characters and this exact request shape:

```json
{"approvedAmountPaise":"10000000","currency":"INR","certificationReference":"CERT-DEMO-001","dueDate":"2026-09-30"}
```

`dueDate` is optional and is an ISO local date; it is not required to be future-dated. Amount is a positive signed-`BIGINT` decimal string. `certificationReference` is nonblank, exact, trimmed, and at most 100 characters.

The idempotency identity includes the milestone ID and normalized request fields, so JSON field ordering has no effect. Same actor/key/request returns the stored original `201` response. Reusing a key for a different request, including another milestone, returns `409 IDEMPOTENCY_KEY_REUSED`. Validation and rolled-back requests are never stored as completed idempotency responses.

```json
{
  "milestoneId":"<milestoneId>",
  "status":"CERTIFIED",
  "certificationReference":"CERT-DEMO-001",
  "certifiedAt":"2026-09-16T10:00:00Z",
  "demand":{"id":"<demandId>","milestoneId":"<milestoneId>","projectId":"<projectId>","reference":"DEM-<UUID>","amountPaise":"10000000","allocatedPaise":"0","outstandingPaise":"10000000","currency":"INR","status":"OPEN","dueDate":"2026-09-30"}
}
```

`DEM-<UUID>` is generated from the persisted demand UUID. Stored certification replays preserve their original snapshot; later demand GETs return current ledger-derived balances.

## Bank webhook acceptance

`POST /webhooks/bank` requires `X-Bank-Timestamp` (Unix seconds) and `X-Bank-Signature: sha256=<hex>`. The server signs/verifies the exact raw body as UTF-8 `timestamp + "." + body` using HMAC-SHA256, constant-time comparison, and a five-minute timestamp tolerance. Project, account, and source come solely from trusted server configuration. `demandReference` may be omitted or `null`; a supplied reference is exact, nonblank, and cannot have surrounding whitespace. Receipt identity is `(source, bankReceiptId)`, independent of delivery `eventId`.

```json
{"eventId":"evt-001","receipt":{"bankReceiptId":"bank-r001","amountPaise":"4000000","currency":"INR","demandReference":"DEM-DEMO-001","postedAt":"2026-09-16T10:00:00Z"}}
```

Successful acceptance commits one `inbox_event` before replying:

```json
{"ingestionEventId":"<local inbox UUID>","status":"PENDING","duplicate":false}
```

An identical replay of `(source, eventId)` returns the same local UUID and status with `duplicate:true`; changed normalized receipt fields return `409 EVENT_ID_CONFLICT`. Signature failures return `401 INVALID_SIGNATURE` or `401 STALE_SIGNATURE`. A failed inbox write returns `503`, never `202`.

## Day 3 processing and inspection responses

Receipt comparison is independent of delivery IDs and the old inbox replay hash. It hashes trusted source/project/account scope, bank receipt ID, positive amount, INR, nullable exact reference, and the PostgreSQL-microsecond-normalized posting timestamp. Existing inbox hashes are preserved. An identical bank receipt identity links to the original receipt with no new money; changed facts end the delivery as `CONFLICT` and open a case.

`GET /ingestion-events/{eventId}` returns `id`, `source`, `status`, trusted `projectId`/`accountReference`, `bankReceiptId`, string `amountPaise`, `currency`, nullable `demandReference`, `postedAt`, `attemptCount`, `receivedAt`, `nextAttemptAt`, nullable `lastErrorCode`, nullable `receiptId`, nullable `processedAt`, and `origin` (`WEBHOOK` for existing deliveries). Terminal statuses are `PROCESSED`, `CONFLICT`, and `FAILED`. `GET /ingestion-events?status=...&limit=...&cursor=...` returns `{"items":[...],"nextCursor":null|"..."}` with those same event objects. `status` is optional and must be `PENDING`, `PROCESSED`, `CONFLICT`, or `FAILED`.

`POST /ingestion-events/{eventId}/retry` takes `{"reason":"bank corrected delivery"}` plus CSRF and a nonblank `Idempotency-Key`. The manager-only operation accepts only `FAILED` rows, resets their retry budget, and returns `202 {"ingestionEventId":"<UUID>","status":"PENDING","duplicate":false}`. Exact same-key replay returns the original response snapshot. A different request with that key returns `409 IDEMPOTENCY_KEY_REUSED`; nonfailed events return `409 EVENT_NOT_RETRYABLE`.

`GET /receipts/{receiptId}` returns immutable `id`, `source`, `bankReceiptId`, `projectId`, string `amountPaise`, `currency`, nullable `demandReference`, `postedAt`, `recordedAt`, and derived string `allocatedPaise`/`unallocatedPaise` and `status` (`UNALLOCATED`, `PARTIALLY_ALLOCATED`, `ALLOCATED`). A receipt always has one `RECEIPT` history entry; allocation entries do not add to inflow totals.

`GET /financial-entries?receiptId=<UUID>` or `?demandId=<UUID>` requires exactly one filter. It returns the page wrapper with entry objects containing `id`, `kind`, `receiptId`, nullable `demandId`, string `amountPaise`, nullable `inboxEventId`, `reason`, and `createdAt`. `kind` is currently `RECEIPT` or `ALLOCATION`; the schema also reserves `ALLOCATION_REVERSAL` for later work, but no reversal action exists now.

`GET /exceptions?status=OPEN|RESOLVED` returns the same page wrapper around `id`, `type`, nullable `receiptId`, `source`, `bankReceiptId`, `reasonCode`, `status`, nullable string `residualAmountPaise`, `firstSeenAt`, `lastSeenAt`, and `supportedActions`. Types currently exposed are `UNALLOCATED_FUNDS` and `BANK_RECORD_CONFLICT`; reasons include `MISSING_REFERENCE`, `UNKNOWN_REFERENCE`, `EXCESS_PAYMENT`, and `FACTS_CHANGED`. Residual amount is derived from the linked receipt rather than stored. `supportedActions` is an empty array in Day 3: no note, allocation, or resolution action is callable yet.

All three new collections use project-scoped keyset pagination: optional `limit` defaults to 50 and must be 1–100; an opaque `cursor` is bound to that collection/filter. Malformed or mismatched cursors, invalid filters/IDs, and wrong filter combinations return `400 VALIDATION_ERROR`. Unknown or out-of-scope resources return `404 NOT_FOUND`.

## Errors

Errors use the correlated envelope:

```json
{"error":{"code":"VALIDATION_ERROR","message":"Invalid request.","requestId":"<requestId>"}}
```

Important codes include `IDEMPOTENCY_KEY_REQUIRED` (400), `IDEMPOTENCY_KEY_REUSED` (409), `MILESTONE_ALREADY_CERTIFIED` (409), `CERTIFICATION_REFERENCE_CONFLICT` (409), `EVENT_ID_CONFLICT` (409), `EVENT_NOT_RETRYABLE` (409), `UNSUPPORTED_CURRENCY` (422), `UNAUTHENTICATED`/`INVALID_SIGNATURE`/`STALE_SIGNATURE` (401), `FORBIDDEN` (403), `NOT_FOUND` (404), and `DEPENDENCY_UNAVAILABLE` (503).
