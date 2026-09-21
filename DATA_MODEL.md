# Data Model

## Conventions and invariants

- PostgreSQL; UUID primary keys, `timestamptz` UTC timestamps. Fields below omit `id` where obvious.
- Money uses signed `bigint` paise, serialized as decimal strings in JSON. Inputs must fit `bigint`; use checked arithmetic. INR only. Demand, receipt, and allocation inputs are strictly positive.
- References are case-sensitive exact strings. Reject leading/trailing whitespace; do not apply fuzzy normalization.
- Posted certification and receipt facts are immutable. Runtime grants permit receipt row locking only; receipt trigger protection and financial-entry privilege revocation prevent fact mutation. Migrations use a separate privileged role.
- For receipt `r`: `unallocated(r) = receipt.amount - SUM(allocation effects for r)`.
- For demand `d`: `outstanding(d) = demand.amount - SUM(allocation effects for d)`.
- `ALLOCATION` effects are positive; `ALLOCATION_REVERSAL` effects are negative. Both balances must remain nonnegative. Receipt money equals net allocated plus unallocated money.
- No writable balance columns. Cross-row limits use shared transactional locking; row checks alone cannot enforce aggregate invariants.

## Entities and fields

| Entity | Key fields |
| --- | --- |
| `client` | `name` (synthetic). |
| `project` | `client_id`, unique `code`, `name`, `currency='INR'`. |
| `milestone` | `project_id`, `sequence`, `name`, nullable `certified_amount_paise`, `certification_reference`, `certified_at`, `certified_by`. Certification fields are all null or all populated. |
| `demand` | `milestone_id`, `project_id`, unique `reference`, `amount_paise`, optional `due_date`, `created_at`. Amount equals the certified amount at creation. |
| `inbox_event` | Delivery `event_id`, trusted project/account and normalized bank facts, nullable exact reference, original delivery replay hash, nullable receipt link, origin, retry metadata, and timestamps. V5 rows retain their hash and become `WEBHOOK` origin without rewriting facts. |
| `receipt` | Implemented immutable facts: unique `(source, bank_receipt_id)`, trusted project, positive INR amount, nullable reference, posting/recording timestamps, and an independent receipt-fact hash. |
| `financial_entry` | Implemented append-only `RECEIPT`, `ALLOCATION`, and future-compatible `ALLOCATION_REVERSAL` shapes. One positive receipt entry exists per receipt; allocation effects derive balances. |
| `exception_case` | Deduplicated residual-funds, bank-record-conflict, and local-receipt-not-in-bank cases with lifecycle/timestamps and receipt/bank identity links. Do not store an authoritative residual amount. |
| `audit_event` | `actor_id`, `action`, `entity_type`, `entity_id`, optional `reason`, `request_id`, `created_at`. Append-only; excludes raw sensitive payloads. Includes exception notes and transitions. |
| `reconciliation_run` | Trusted `source`, `project_id`, `account_reference`, initiating `actor_id`/`request_id`, status, durable phase, nullable `snapshot_id`/`as_of`/`next_cursor` and local-absence cursor, lease owner/version/expiry, fetch attempts/page count/next attempt, counts, timestamps, and safe error code. |
| `reconciliation_cursor` | Unique `(run_id, cursor_value)` history rejects cursor loops after restart. |
| `reconciliation_item` | Unique `(run_id, bank_receipt_id)`, validated amount/currency/reference/posting time, canonical receipt hash, nullable inbox event link, and durable outcome. Identical repeated bank rows deduplicate. |
| `reconciliation_exception` | Unique `(run_id, exception_id)` historical association of observed or resolved discrepancy cases to the run that compared them. |
| `idempotency_request` | `actor_id`, `operation`, `key`, `request_hash`, `response_status`, `response_body`, `created_at`. Unique actor/operation/key; written atomically with successful user mutation. Retain for the prototype lifetime. |

Actors are a fixed registry for configured Basic-auth identities (`CERTIFIER`, `ACCOUNTS`, `MANAGER`) and named system actors; it is not a user-management system and has no user-management API. The runtime role can read the setup registry/client/project data but cannot modify clients or projects. Client identity is derived through the receipt's project; matching never infers a project from an untrusted reference.

### Financial entry rules

- `RECEIPT`: positive amount equal to its receipt; no demand or reversal link; exactly one per receipt.
- `ALLOCATION`: positive amount; receipt and demand required and in the same project.
- `ALLOCATION_REVERSAL`: negative amount; same receipt/demand and opposite amount of its original `ALLOCATION`; exactly one full reversal per original entry.
- Use row checks for entry shape and sign; validate cross-row equivalence and limits in the transaction service under locks. Foreign keys use restrictive deletion.
- This is an append-only collections subledger, not a full double-entry general ledger. Receipt entries establish inflow; allocation entries describe its application and must not be added to inflow totals.

## Relationships

Client 1:N projects; project 1:N milestones and receipts; milestone 0:1 demand; receipt N:M demands through allocation entries. Each receipt has one receipt entry and may have multiple allocations/reversals. Inbox events may converge on one receipt. Reconciliation items can reference inbox events shared with processing. Exceptions may refer to a receipt or an unresolved bank identity.

## Status lifecycle

| Object | Lifecycle |
| --- | --- |
| Milestone | Derived: `SCHEDULED → CERTIFIED`; no uncertify/amend operation. |
| Demand | Derived: `OPEN` (zero net allocation), `PARTIALLY_PAID`, `SETTLED` (zero outstanding). Reversal can reopen it. |
| Receipt | Derived: `UNALLOCATED`, `PARTIALLY_ALLOCATED`, `ALLOCATED`; reversal can move it backward. |
| Inbox | `PENDING → PROCESSED` or `CONFLICT`; transient failures return to pending with backoff, then `FAILED`; authorized retry returns failed rows to pending. |
| Exception | `OPEN ↔ RESOLVED`, with each transition audited. Residual-funds cases resolve at zero residual; bank discrepancies resolve only when a later complete comparison verifies agreement. Notes do not close cases. |
| Reconciliation | `QUEUED → RUNNING → COMPLETED / COMPLETED_WITH_ERRORS / FAILED`; expired running leases resume from persisted state. A failed run can be followed by a new run. |

Exception types: `UNALLOCATED_FUNDS` with reasons `MISSING_REFERENCE`, `UNKNOWN_REFERENCE`, `EXCESS_PAYMENT`, or `ALLOCATION_REVERSED`; `BANK_RECORD_CONFLICT`; `LOCAL_RECEIPT_NOT_IN_BANK`. Changed reuse of a delivery event ID is an ingestion conflict surfaced to the sender and audited; it does not create another receipt.

## Indexes and constraints

- Unique `milestone(project_id, sequence)`; unique `demand(milestone_id)` and `demand(reference)`.
- Composite foreign key from demand `(milestone_id, project_id)` to the matching milestone identity/project.
- Unique `inbox_event(source, event_id)` and `receipt(source, bank_receipt_id)`.
- Partial inbox index `(next_attempt_at, received_at) WHERE status='PENDING'`; index failed inbox rows for operations.
- Partial unique `financial_entry(receipt_id) WHERE kind='RECEIPT'`; unique non-null `reverses_entry_id`.
- Index `financial_entry(receipt_id, created_at)` and `(demand_id, created_at)` for balance/history reads.
- Index `demand(project_id, created_at, id)` and `receipt(project_id, recorded_at, id)` for worklists.
- Unique `exception_case(dedupe_key)`; index `(project_id, status, first_seen_at, id)`. Residual-funds key is receipt ID plus type; discrepancy key is source/bank receipt ID plus type.
- Unique `reconciliation_item(run_id, bank_receipt_id)`; partial unique active run `(source, project_id) WHERE status IN ('QUEUED','RUNNING')`.
- Unique `reconciliation_cursor(run_id, cursor_value)` prevents a cursor loop; due and recent-run indexes support worker claims and worklist freshness.
- Unique `idempotency_request(actor_id, operation, key)`; index audit events by entity and time.

V6 follows V5 without editing applied migrations: it relaxes only the inbox reference nullability, adds a check rejecting supplied blank/padded references, creates receipts/entries/exceptions, and adds receipt linkage. V7 grants `UPDATE(id)` on demand solely for PostgreSQL row locking; a trigger rejects actual demand fact changes. V8 adds `(project_id, created_at, id)` and `(project_id, recorded_at, id)` worklist indexes without changing financial records. Runtime `UPDATE(id)` on receipt similarly enables `FOR UPDATE`, while its trigger rejects posted-fact changes. Runtime can read/insert financial entries but cannot update/delete them, and can update only operational inbox/exception columns. Investigation notes are append-only `audit_event` rows with `action=EXCEPTION_NOTE`, `entity_type=EXCEPTION`, and the exception ID. Owners retain migration authority. Receipt and demand balances are derived with exact aggregate conversion; the API rejects out-of-range or negative/over-amount ledger states rather than truncating them.

V9 adds only reconciliation operations and the third exception type. It grants the runtime role select/insert on the new tables and updates only to run progress and item outcome/link fields. Cursor and run/exception links are insert-only. It grants no additional receipt, demand, ledger, or audit mutation privilege. The V8 upgrade test preserves a pending inbox event, demand, receipt, receipt entry, allocation, exception, and investigation note through the new migrations.

V10 adds a nullable local-absence cursor to the run so each scan transaction handles at most 100 eligible local receipts and can resume from its committed position. The V8 upgrade test now migrates through V10 and verifies its existing demand and allocation as well.

## Optional outbox extension

`outbox_event(id, aggregate_id, event_type, payload, created_at, delivered_at, attempt_count, next_attempt_at, last_error_code)`; unique `(aggregate_id, event_type)` for the one-time `DemandCreated` event, and a partial pending index on `next_attempt_at`. The simulated receiver keeps a unique consumed-event ID. This schema is unnecessary unless outbound delivery is implemented.
