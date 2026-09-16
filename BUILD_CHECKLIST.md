# Milestone Ledger — Build Checklist

This is the working checklist for the API-only MVP. The goal is a trustworthy five-day build, not a feature-rich demo. A checkmark means the item is complete and evidenced; a design document is not the same as implementation.

## Overall project checklist

- [x] 1. Product thesis — defined in [PRD.md](PRD.md).
- [x] 2. Core loop — review demands → process receipts → resolve exceptions → trust the balance.
- [x] 3. MVP scope — one synthetic project/client, INR, API-only, simulated bank.
- [x] 4. Out-of-scope list — defined in [PRD.md](PRD.md).
- [x] 5. PRD — [PRD.md](PRD.md).
- [x] 6. System design — [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md).
- [x] 7. Data model — [DATA_MODEL.md](DATA_MODEL.md).
- [x] 8. API contract — [API_CONTRACT.md](API_CONTRACT.md).
- [x] 9. AI rules file — [AI_RULES.md](AI_RULES.md).
- [ ] 10. Repo setup — Maven Wrapper, Spring Boot app, Docker Compose, Flyway, configuration templates, `.gitignore`, and clean local startup.
- [ ] 11. CI + tests — GitHub Actions runs `./mvnw verify`; unit and PostgreSQL integration tests are wired in.
- [ ] 12. Feature-by-feature build — certification, demand, inbox, receipt processing, allocation, exceptions, reconciliation, worklist/history.
- [ ] 13. Security + failure handling — roles, Basic auth, webhook HMAC, validation, idempotency, atomic rollback, retries, and restart recovery are implemented and tested.
- [ ] 14. Observability — structured logs, audit events, health checks, and visible pending/failed-work status are implemented.
- [ ] 15. Staging — optional; a deployed synthetic-data environment is smoke-tested only after local Docker and CI pass.
- [ ] 16. Production — deliberately not part of this portfolio MVP. Do not check this without a real-data, security, operations, and legal review.
- [ ] 17. Maintenance review — final dependency, secret, backup/restore, runbook, and known-limitations review.

## How every feature is built

For each feature, complete the relevant items in [DEFINITION_OF_DONE.md](DEFINITION_OF_DONE.md):

- [ ] Requirement and acceptance example are clear.
- [ ] Database migration and constraints are complete.
- [ ] Backend use case and API/worker behavior are complete.
- [ ] Frontend is `N/A` for this MVP; the Postman collection/demo script proves the workflow.
- [ ] Validation, typed errors, authorization, audit trail, and structured logging are complete where applicable.
- [ ] Unit and PostgreSQL integration tests pass, including failure behavior.
- [ ] Contract, setup, and demo documentation are updated.
- [ ] Diff is reviewed, the change is understood, and the focused commit is ready.

## Five-day build plan

### Day 1 — Foundation and a reproducible local environment

- [ ] Initialize the Maven/Spring Boot project using Java 21 and the agreed package structure.
- [ ] Add Docker Compose for PostgreSQL 17 and the app; add a minimal simulated-bank fixture or adapter contract.
- [ ] Add Flyway, Spring JDBC, Spring Security, validation, test dependencies, and configuration templates.
- [ ] Create the first schema migration: project, client, milestone, demand, audit, users/roles, and idempotency tables needed for Day 2.
- [ ] Seed only fictional users, project, client, and milestones; startup must not overwrite user-created data.
- [ ] Add liveness/readiness health endpoints and structured JSON logging with correlation IDs.
- [ ] Add a Testcontainers smoke test proving migrations run on real PostgreSQL.
- [ ] Document `docker compose up` and `./mvnw verify`; commit the foundation as one focused change.

**Day 1 exit gate:** A fresh checkout starts locally, migrates a clean PostgreSQL database, exposes health, and passes a database-backed smoke test.

### Day 2 — Certification and demand creation

- [ ] Implement role configuration with separate `CERTIFIER`, `ACCOUNTS`, and `MANAGER` identities.
- [ ] Implement milestone reads and certification/demand creation endpoints.
- [ ] Enforce one demand per milestone, INR, positive integer-paise money, idempotency, and atomic certification+demand writes.
- [ ] Return contract-defined validation, authorization, conflict, and idempotency errors.
- [ ] Write unit tests for money/reference rules and PostgreSQL integration tests for repeat and concurrent certification.
- [ ] Record audit events and safe structured logs; update the API collection and documentation.

**Day 2 exit gate:** A certifier can create one demand, retries do not create another, an unauthorized user cannot certify, and no failed request leaves partial data.

### Day 3 — Durable receipt ingestion and automatic matching

- [ ] Add receipt, financial-entry, inbox-event, and exception schema migrations with unique constraints and indexes.
- [ ] Implement HMAC-verified webhook acceptance that durably inserts an inbox event before returning `202`.
- [ ] Implement the database-backed worker, retry state, receipt identity deduplication, and exact-reference matching.
- [ ] Allocate only `min(receipt available, demand outstanding)` in one transaction; leave unknown/excess money explicitly unallocated.
- [ ] Add simulator scenarios for duplicate events, new delivery IDs for the same receipt, and distinct equal-value receipts.
- [ ] Test concurrent duplicate delivery, atomic rollback, signature failures, and worker restart against PostgreSQL.

**Day 3 exit gate:** Five concurrent deliveries of one receipt change the balance once; two genuine equal-value receipts both persist; a crash cannot leave partial money state.

### Day 4 — Collections worklist, exceptions, and manual allocation

- [ ] Add demand, receipt, financial-history, exception, and failed-event read APIs with pagination and documented filters.
- [ ] Ensure the outstanding worklist includes both open and partially paid demands; show pending/failed ingestion freshness separately from recorded balances.
- [ ] Implement authorized manual allocation with mandatory reason, idempotency, project scoping, shared lock order, and audit history.
- [ ] Implement append-only exception notes; do not allow notes to silently close discrepancies.
- [ ] Test competing automatic/manual allocations, excess funds, cross-project access, and all role restrictions.
- [ ] Update the prepared API collection so a first-time user can answer what is due, what arrived, and what needs attention in under two minutes.

**Day 4 exit gate:** An accounts user can resolve an evidenced unmatched receipt without over-allocating money; every remaining difference stays visible and explainable.

### Day 5 — Reconciliation, full proof, CI, and demo

- [ ] Implement durable reconciliation runs, stable simulated-bank snapshots, staged comparison, and missing-receipt recovery through the normal inbox path.
- [ ] Prevent concurrent runs for the same source/project; make incomplete snapshots and failed recovery visible.
- [ ] Create discrepancies for changed bank records and eligible local records missing from a complete snapshot; never overwrite posted money.
- [ ] Add full end-to-end integration scenarios: partial then final payment, duplicate delivery, missing notification recovery, reconciliation/webhook race, and restart recovery.
- [ ] Add GitHub Actions to run `./mvnw verify`; verify Docker startup from a clean database.
- [ ] Write the five-minute demo script and README: scope, setup, test commands, architecture, guarantees, and limitations.

**Day 5 exit gate:** CI is green; Docker reproduction works; the five-minute demo proves duplicate protection and missing-event recovery using persisted results.

## Day 6 — Buffer and quality gate

Do not add features before fixing failed acceptance gates.

- [ ] Re-run all acceptance scenarios on the final revision and resolve failures, flaky tests, unsafe logs, and setup gaps.
- [ ] Review migrations, indexes, lock order, retry behavior, error responses, and secrets with the risk review.
- [ ] Confirm the demo starts from a clean database and uses synthetic data only.
- [ ] Improve the README and API collection only where they remove real setup or explanation friction.
- [ ] If all five-day gates pass early, implement **full allocation reversal** with manager authorization, preserved history, and concurrency tests. Otherwise defer it.
- [ ] Make a maintenance-review list: dependency versions, backup/restore procedure, host configuration, monitoring gaps, and known limitations.

## Working rules during the project

1. **Build feature by feature, not screen by screen.** Complete database → backend → API → frontend/API demo → tests → logs → docs for each feature.
2. **Give AI small tasks.** Ask for one bounded use case, relevant tests, and explicit non-goals. Do not use “build my app.”
3. **Use a small context packet.** Include goal, relevant files, constraints, expected output, and files/behavior that must not change.
4. **Plan → review → implement → test → diff review → commit.** Skip the plan only for trivial, low-risk edits.
5. **Commit small.** One feature, bug fix, or refactor per commit.
6. **Never merge code you cannot explain.** Be able to explain the transaction boundary, uniqueness rule, authorization decision, and test evidence.
7. **Run tests continuously.** Run focused tests while building and `./mvnw verify` before handoff. Critical financial flows require database integration tests.
8. **Add failure handling early.** Design timeouts, retries, duplicate protection, error responses, audit history, and restart behavior with the feature.
9. **Track logs and errors from the beginning.** For each failure, record what happened, when, which safe internal IDs were involved, and which application version handled it. Never log secrets or bank payloads.
