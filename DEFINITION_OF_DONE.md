# Milestone Ledger — Definition of Done

**A feature is done only when its shared checklist and feature-specific checks pass.** Working code alone is insufficient. All boxes start unchecked; this document does not claim implementation exists.

Use one feature card per task. Record evidence as test names, commands/results, or document links. Reuse shared test evidence; do not create duplicate tests or paperwork. Mark an item `N/A — reason` only when genuinely inapplicable. A blocked required check means the feature is not done.

Scope: Java/Spring Boot modular monolith, PostgreSQL, simulated bank, synthetic data, one project/client, INR. Read alongside [PRD](PRD.md), [system design](SYSTEM_DESIGN.md), [data model](DATA_MODEL.md), [API contract](API_CONTRACT.md), and [AI rules](AI_RULES.md).

## Shared checklist — apply to every feature

- [ ] **1. Requirement clarity:** Actor, input, expected outcome, acceptance example, boundaries, and failure behavior are written down. Relevant PRD requirement is identified.
- [ ] **2. Backend/API complete:** Use case works end to end through the documented API or worker. Correct status codes, DTOs, SQL, migrations, transactions, and idempotency are implemented where applicable. No success-returning stubs.
- [ ] **3. Frontend complete:** `N/A — API-only MVP`. The API collection/script can exercise the feature and show its result. No UI is required to mark it done.
- [ ] **4. Validation:** Required fields, amount bounds, INR, reference format, resource scope, and state transitions are enforced where applicable. Database constraints back critical invariants.
- [ ] **5. Error handling:** Invalid input, unauthorized actions, conflicts, and applicable dependency failures have predictable outcomes. Failed writes cannot leave partial financial state; retryable failures are distinguishable.
- [ ] **6. Unit tests:** Meaningful isolated business rules and edge cases pass. If no isolated logic exists, record N/A and cite integration coverage; do not test getters or duplicate framework tests.
- [ ] **7. Integration tests:** Applicable API, PostgreSQL, authorization, concurrency, and rollback paths pass. Mocks do not substitute for database correctness. Add regression coverage for defects.
- [ ] **8. Security checks:** Role/resource restrictions and input handling are tested. No real data or secrets enter code, fixtures, responses, or logs. Simulator controls remain private.
- [ ] **9. Logging:** Important outcomes/failures are traceable using internal IDs and error codes, without sensitive payloads. Financial mutations have persistent audit evidence. Avoid noisy success logs for every read.
- [ ] **10. Documentation:** Update affected API examples, business rules, configuration, migrations, and demo steps. State limitations; do not claim unsupported guarantees.
- [ ] **11. Deployment readiness:** Feature runs with the documented configuration in Docker. Required variables are documented without secrets; state survives restart where required. Applicable migrations and `./mvnw verify` pass. Public cloud deployment is not required.

## Feature-specific checks

### A. Synthetic project and milestone setup

- [ ] One fictional client/project, INR, and a fixed milestone schedule are available through read APIs.
- [ ] Setup is repeatable without duplicate records; normal startup does not reset financial history.
- [ ] Database constraints reject invalid project/milestone relationships.
- [ ] Fresh Docker startup and restart preserve expected fixtures and user-created records.

### B. Certification and demand creation — F1

- [ ] Certifier records approved net amount and certification reference; one demand is created in the same transaction.
- [ ] Invalid amount/currency/reference and unauthorized roles are rejected. Manager role alone cannot certify.
- [ ] Retrying the same idempotency key returns the original response; changed reuse conflicts. Concurrent certification cannot create two demands.
- [ ] A failed operation leaves neither partial certification nor an orphan demand; actor and timestamp are recorded.
- [ ] Posted certification facts cannot be amended through the MVP API.

### C. Signed webhook and durable inbox — F2, F4

- [ ] Valid signed receipt notification is persisted before `202`; database unavailability cannot produce a success acknowledgment.
- [ ] Missing/invalid signatures, stale timestamps, malformed amounts, unsupported currency, and oversized input are rejected.
- [ ] Identical delivery-ID replay returns the same inbox identity; changed reuse conflicts without overwriting the original.
- [ ] Account/project context comes from trusted adapter configuration, not caller-controlled fields.
- [ ] Pending event survives an actual application restart and later completes.

### D. Receipt processing, matching, and ledger — F3, F4, F5, F9

- [ ] Exact-reference matching allocates only available money; partial payment leaves outstanding money and excess stays explicitly unallocated.
- [ ] One receipt delivered five times, sequentially and concurrently, creates one receipt and one financial result, including when delivery IDs differ.
- [ ] Two genuine equal-value receipts both persist. Changed fields under an existing bank receipt ID create a conflict without changing money.
- [ ] Concurrent receipts and manual allocations cannot exceed receipt or demand limits; every path uses the same lock order.
- [ ] Fault injection between writes proves atomic receipt, ledger, allocation, exception, and event-completion behavior. No network calls hold financial locks.
- [ ] Each receipt equals net allocated plus unallocated money; each demand equals net allocated plus outstanding money. Posted ledger entries cannot be edited/deleted by the runtime database role.

### E. Exceptions and manual allocation — F5, F6

- [ ] Unknown/missing references and excess receipts produce a visible reason and permitted next action.
- [ ] Authorized allocation requires demand, amount, and reason; both balance limits and project scope are checked under locks.
- [ ] Same-key retries do not allocate again; competing requests cannot overspend. Full allocation resolves the residual-funds case; partial allocation leaves it open.
- [ ] Investigation notes preserve actor/time and do not close financial discrepancies or change balances.
- [ ] Unsupported refunds or bank corrections remain visibly unresolved; users are never forced to invent a match.

### F. Full allocation reversal — F6, seven-day scope

- [ ] Manager can append a full opposite entry linked to the original allocation, with a mandatory reason.
- [ ] Original entry remains unchanged; outstanding and unallocated balances are restored consistently.
- [ ] Duplicate/concurrent reversal is prevented; partial reversal and reversal of a reversal are rejected.
- [ ] Residual-funds exception reopens or is created; no automatic rematching occurs. Explicit reallocation works afterward.

For the five-day version, mark this feature **Deferred**, not Done, and document that posted allocations cannot be corrected through that version.

### G. Reconciliation — F7

- [ ] Authorized request returns a durable run ID. Only one run per source/project is active; status and counts are inspectable.
- [ ] A missed notification is recovered once. Repeated runs and a racing webhook cannot duplicate the receipt.
- [ ] Pagination uses a stable complete snapshot. A failed page or expired worker lease resumes safely or yields a visible failure, never a false complete comparison.
- [ ] Changed bank fields and eligible local receipts absent from the snapshot produce discrepancies without modifying posted money.
- [ ] Run completion waits for recovery processing; failed ingestion yields an error outcome. Successful comparison does not imply zero matching exceptions.
- [ ] Recovered, conflicting, and failed counts match persisted results; bank outage/retry behavior is demonstrated.

### H. Collections worklist and history — F8

- [ ] User can retrieve demands, receipts, allocation/reversal history, and unresolved exceptions with consistent balances.
- [ ] The outstanding-worklist example includes both `OPEN` and `PARTIALLY_PAID` demands; settled demands are excluded from that view.
- [ ] Empty results, missing IDs, invalid filters, and pagination boundaries work predictably.
- [ ] Response totals use a consistent database snapshot; pending ingestion is not represented as recorded money.
- [ ] Processing freshness and pending/failed ingestion are available to the collections workflow through a documented, authorized response. Define any additive API fields before implementing them.
- [ ] On a documented local machine with 1,000 receipts, measure the PRD targets: worklist below one second and normal processing within five seconds; disclose misses.

### I. Authentication and role enforcement — supports all features

- [ ] Seeded identities use configured password hashes; missing credentials fail closed and errors reveal no secrets.
- [ ] Automated permission matrix covers reads, certification, allocation, notes, reconciliation, reversal, and retry.
- [ ] Resource scope is enforced even when a valid UUID is supplied; no mutation bypasses application-service authorization.
- [ ] Webhook authentication is isolated from user authentication. Any CSRF exemption is narrow and justified for the supported client model.
- [ ] Local binding is documented. If publicly hosted, HTTPS, request limits, login throttling, and protected operational endpoints are checked.

### J. Worker retry and operational visibility — F9

- [ ] Transient failures persist bounded retry/backoff state; exhausted work becomes visibly failed and does not block unrelated events.
- [ ] Authorized retry requires a reason and cannot duplicate completed financial effects.
- [ ] Restart recovers pending inbox work and abandoned reconciliation leases against the same database.
- [ ] Operators can inspect failed work, oldest pending age, and reconciliation freshness; database health alone is not treated as worker progress.
- [ ] Logs connect acceptance, processing, and failure through internal IDs. Missing dependencies produce useful readiness/failure signals.

### K. Packaging, CI, and proof demo

- [ ] Fresh checkout starts using documented prerequisites and commands; credentials are supplied separately.
- [ ] Flyway works on a clean database and from the previous applicable schema version. Normal restart does not reseed or erase history.
- [ ] CI runs unit and PostgreSQL integration tests through `./mvnw verify`; no essential tests are silently skipped.
- [ ] In under two minutes, the prepared API collection shows what is due, what arrived, and what needs investigation.
- [ ] Five-minute script proves duplicate handling and missing-event recovery, showing persisted balances rather than canned responses.
- [ ] Final diff, fixtures, logs, and demo output contain no secrets or real client data. Known limitations are documented.

### Optional: outbound transactional outbox

Not required for either core release. Apply only if explicitly added to scope after correctness gates pass.

- [ ] Certification and `DemandCreated` outbox insertion commit together.
- [ ] Receiver failure triggers durable retry without losing the event.
- [ ] Crash after delivery but before acknowledgment causes a harmless repeat; receiver deduplicates by event ID.
- [ ] Documentation states at-least-once delivery and shows evidence of one receiver-side effect.

## Solo-developer completion record

Copy this into the feature task or commit/PR notes:

```text
Feature / PRD requirement:
Shared checklist: complete / outstanding items
Feature checks: complete / outstanding items
Evidence: test names, commands/results, demo or document links
N/A or deferred items and reasons:
Known limitations:
Status: Done / In progress / Blocked / Deferred
```

Do not require a separate reviewer or a numerical coverage target. Review your own diff, prove the relevant behavior, and keep the evidence reproducible. Before calling the whole MVP done, run the complete acceptance suite on the final code revision; documentation-only changes need only document checks.
