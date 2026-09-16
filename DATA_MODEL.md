# Data Model

## Conventions and invariants

- PostgreSQL; UUID primary keys, `timestamptz` UTC timestamps. Fields below omit `id` where obvious.
- Money uses signed `bigint` paise, serialized as decimal strings in JSON. Inputs must fit `bigint`; use checked arithmetic. INR only. Demand, receipt, and allocation inputs are strictly positive.
- References are case-sensitive exact strings. Reject leading/trailing whitespace; do not apply fuzzy normalization.
- Posted receipts, certification facts, and financial entries cannot be updated/deleted by the runtime database role. Operational state can change; retain its audit events. Migrations use a separate privileged role.
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
| `inbox_event` | `source`, `event_id`, `bank_receipt_id`, minimal normalized receipt JSON, `canonical_hash`, `origin` (`WEBHOOK`/`RECONCILIATION`), `status`, `attempt_count`, `next_attempt_at`, `last_error_code`, optional `receipt_id`, `received_at`, `processed_at`. |
| `receipt` | `source`, `bank_receipt_id`, `project_id`, `amount_paise`, `currency`, nullable `demand_reference`, `posted_at`, `recorded_at`, `canonical_hash`. Source is a configured adapter/account alias, not arbitrary user text. |
| `financial_entry` | `kind`, `receipt_id`, nullable `demand_id`, signed `amount_paise`, nullable `reverses_entry_id`, `actor_id`, `reason`, optional `inbox_event_id`, `created_at`. |
| `exception_case` | `type`, `project_id`, optional `receipt_id`, optional `source`/`bank_receipt_id`, unique `dedupe_key`, `status`, `reason_code`, `first_seen_at`, `last_seen_at`, optional `resolved_at`. Do not store a separate authoritative unallocated amount. |
| `audit_event` | `actor_id`, `action`, `entity_type`, `entity_id`, optional `reason`, `request_id`, `created_at`. Append-only; excludes raw sensitive payloads. Includes exception notes and transitions. |
| `reconciliation_run` | `source`, `project_id`, `status`, nullable `snapshot_id`/`as_of`/`next_cursor`, `lease_until`, `attempt_count`, `bank_count`, `recovered_count`, `conflict_count`, `failed_count`, `started_at`, `finished_at`, `error_code`. |
| `reconciliation_item` | `run_id`, `bank_receipt_id`, canonical receipt JSON/hash, nullable `inbox_event_id`, `outcome`. Durable staging and recovery tracking. |
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
- Unique `idempotency_request(actor_id, operation, key)`; index audit events by entity and time.

## Optional outbox extension

`outbox_event(id, aggregate_id, event_type, payload, created_at, delivered_at, attempt_count, next_attempt_at, last_error_code)`; unique `(aggregate_id, event_type)` for the one-time `DemandCreated` event, and a partial pending index on `next_attempt_at`. The simulated receiver keeps a unique consumed-event ID. This schema is unnecessary unless outbound delivery is implemented.
