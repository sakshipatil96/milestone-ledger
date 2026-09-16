# Certified Milestone Collections — MVP PRD

## Goals

Give a project accounts executive a trustworthy answer to: what is due, what arrived, and what needs investigation. This is a proposed workflow for a contractor like Eagle Infra, not a claim about Eagle's existing systems.

Core loop: review outstanding demands → process receipts → resolve exceptions → review the updated balance.

- Scope: one synthetic project, one client, INR, a seeded milestone schedule, and approved net collectible amounts.
- First value in under two minutes: a prepared API collection shows a ₹1,00,000 demand, imports ₹40,000, and shows ₹60,000 outstanding alongside an unmatched receipt and its resolution action.
- Return trigger: new receipts, outstanding collections, or reconciliation differences.
- Portfolio evidence: executable correctness and recovery scenarios, not feature count. The recruiter evaluates the product; the accounts executive uses it.

## User stories

- As a certifier, I record an approved amount and certification reference so finance has one collectible demand.
- As an accounts executive, I see outstanding demands and the receipt history explaining each balance.
- As an accounts executive, I allocate an unmatched receipt when I have evidence, or leave it explicitly unallocated.
- As a finance manager, I investigate bank differences and reverse a mistaken allocation without erasing history.

## Functional requirements

| ID | Requirement |
| --- | --- |
| F1 | Certification creates one demand atomically; repeated requests cannot create another. Certification is recorded, not approved by this system. |
| F2 | A signed simulated bank webhook is durably accepted before asynchronous processing. Receipt identity is the bank's stable receipt ID, not amount or delivery event ID. |
| F3 | Match only an exact demand reference within the adapter's trusted project/client context. Allocate the lesser of available receipt money and outstanding demand money. |
| F4 | Preserve distinct equal-value receipts. Repeated notifications, including concurrent deliveries, record each receipt once. |
| F5 | Missing/unknown references and excess money produce actionable exceptions. No fuzzy matching. |
| F6 | Authorized manual allocation requires a demand, amount, and reason. Reversal preserves the original entry and requires manager authorization. |
| F7 | Manually triggered reconciliation discovers missed receipts and detects changed or locally unsupported records. It uses the same ingestion and allocation rules as webhooks. |
| F8 | Read APIs expose demands, receipts, append-only financial entries, exceptions, and reconciliation results. |
| F9 | Retry transient failures durably. Restart after event persistence must complete processing without duplicate money. |

## Non-functional requirements

- **Money:** integer paise, INR only, positive receipt/demand amounts; never floating point. Every recorded receipt equals its net allocations plus its explicitly unallocated balance.
- **Concurrency:** database uniqueness and row locks enforce identity and allocation limits. Application prechecks alone are insufficient.
- **Atomicity:** receipt creation, automatic allocation, exception changes, and successful event processing commit together.
- **Audit:** financial entries are append-only. Record actor, reason, timestamps, and source IDs. Certification and receipt facts are immutable once posted.
- **Security:** role checks on every operation; webhook HMAC verification; synthetic data only; secrets outside source control. Logs contain IDs and outcomes, not credentials, signatures, or bank payloads.
- **Recovery:** durable work survives restart. Expose pending/failed ingestion and incomplete reconciliation rather than displaying a false all-clear.
- **Maintainability:** one deployable application and PostgreSQL, schema migrations, Docker Compose, documented startup, real-database integration tests in CI.
- **Prototype performance:** on a documented local test machine with 1,000 receipts, target worklist responses below one second and normal event processing within five seconds. No production SLA is claimed.

### Acceptance gates

1. Certification → partial receipt → final receipt produces a settled demand.
2. Deliver one receipt five times sequentially and concurrently; balance changes once.
3. Two distinct equal-value receipts both persist; concurrent receipts cannot overpay a demand.
4. Concurrent automatic/manual allocations cannot overspend a receipt; excess remains unallocated.
5. Omit a notification; reconciliation imports it once, including on repeated runs.
6. Crash after event persistence and inside processing; restart produces one committed financial result.
7. Resolve an unknown-reference receipt with authorization; unauthorized writes fail. Reverse an allocation and verify restored balances and preserved history.
8. A changed bank record produces a visible discrepancy without overwriting money; incomplete bank reads cannot declare reconciliation complete.
9. All scenarios satisfy balance invariants; CI and the five-minute demo are reproducible.

## Delivery boundary

- **Five days:** F1–F5, F7–F9, plus manual allocation from F6. Posted allocations cannot be corrected through this version; document that limitation.
- **Seven days:** add authorized reversal and stronger concurrency/failure tests. Required correctness gates take priority over extensions.
- **Optional seven-day extension:** transactional outbox delivering `DemandCreated` to a simulated accounting receiver. Add only after the acceptance gates pass; it is not needed for the core collections loop.

## Out of scope

Frontend, real Eagle/bank data, real bank integrations, payment initiation, GST/TDS/retention calculation, certification approval workflows, invoice/ERP replacement, residential bookings, escrow, multiple currencies, configurable workflows, AI/fuzzy matching, forecasting, notifications, and bulk resolution.

Demand amount amendments, bank receipt corrections/refunds, and partial allocation reversals are unsupported. Flag discrepancies for investigation; do not edit posted facts. A full allocation reversal followed by a new allocation supports correcting a match.
