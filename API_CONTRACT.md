# API Contract

## Day 2 implemented surface

All paths are under `/api/v1`, use JSON and UUID resource IDs, and use UTC ISO-8601 timestamps. Monetary values are decimal-string paise. The API currently supports INR only. Unknown JSON fields and numeric/fractional money values are rejected.

Local demo users authenticate with HTTP Basic using externally configured BCrypt password hashes. `CERTIFIER`, `ACCOUNTS`, and `MANAGER` may read project, milestone, and demand data. Only `CERTIFIER` may certify; `MANAGER` does not inherit that permission. Bank webhooks authenticate independently with HMAC and do not use Basic authentication.

| Method and path | Access | Day 2 result |
| --- | --- | --- |
| `GET /projects` | All three roles | Configured project/client metadata. |
| `GET /projects/{projectId}/milestones` | All three roles | Fixed schedule; status becomes `CERTIFIED` after certification. |
| `GET /csrf-token` | Authenticated | Returns a CSRF token and sets the `XSRF-TOKEN` cookie. |
| `POST /milestones/{milestoneId}/certification` | Certifier + CSRF | Creates certification and exactly one demand atomically. |
| `GET /demands/{demandId}` | All three roles | Persisted demand facts with zero allocated and full outstanding balance. |
| `POST /webhooks/bank` | HMAC | Commits a normalized inbox event and returns it as `PENDING`. |
| `GET /ingestion-events/{eventId}` | Manager | Inspects a committed inbox event. |

The full receipt, allocation, financial-entry, exception, reconciliation, and worklist APIs are not implemented. Receipt processing remains Day 3 work; accepted events are deliberately not marked processed.

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

`DEM-<UUID>` is generated from the persisted demand UUID. Allocated/outstanding values are intentionally interim values until Day 3 introduces ledger allocations.

## Bank webhook acceptance

`POST /webhooks/bank` requires `X-Bank-Timestamp` (Unix seconds) and `X-Bank-Signature: sha256=<hex>`. The server signs/verifies the exact raw body as UTF-8 `timestamp + "." + body` using HMAC-SHA256, constant-time comparison, and a five-minute timestamp tolerance. Project, account, and source come solely from trusted server configuration.

```json
{"eventId":"evt-001","receipt":{"bankReceiptId":"bank-r001","amountPaise":"4000000","currency":"INR","demandReference":"DEM-DEMO-001","postedAt":"2026-09-16T10:00:00Z"}}
```

Successful acceptance commits one `inbox_event` before replying:

```json
{"ingestionEventId":"<local inbox UUID>","status":"PENDING","duplicate":false}
```

An identical replay of `(source, eventId)` returns the same local UUID and status with `duplicate:true`; changed normalized receipt fields return `409 EVENT_ID_CONFLICT`. Signature failures return `401 INVALID_SIGNATURE` or `401 STALE_SIGNATURE`. A failed inbox write returns `503`, never `202`.

## Errors

Errors use the correlated envelope:

```json
{"error":{"code":"VALIDATION_ERROR","message":"Invalid request.","requestId":"<requestId>"}}
```

Important Day 2 codes: `IDEMPOTENCY_KEY_REQUIRED` (400), `IDEMPOTENCY_KEY_REUSED` (409), `MILESTONE_ALREADY_CERTIFIED` (409), `CERTIFICATION_REFERENCE_CONFLICT` (409), `EVENT_ID_CONFLICT` (409), `UNSUPPORTED_CURRENCY` (422), `UNAUTHENTICATED`/`INVALID_SIGNATURE`/`STALE_SIGNATURE` (401), `FORBIDDEN` (403), `NOT_FOUND` (404), and `DEPENDENCY_UNAVAILABLE` (503).
