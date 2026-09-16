# Milestone Ledger — AI Rules

Applies to every coding assistant. Read this file and the relevant PRD, system design, data model, and API contract before editing. Follow explicit user instructions; surface material conflicts instead of silently changing requirements. Make the smallest complete change.

## 1. Project purpose

- Track certified milestone receivables, incoming receipts, allocations, and reconciliation exceptions.
- MVP: one project, one client, INR, synthetic data, approved net collectible amounts, API only.
- Prove trustworthy balances under duplicate delivery, concurrency, crashes, and missing notifications. Exclude billing calculations, payment initiation, and certification approval.

## 2. Tech stack

- Java 21, Spring Boot 4.1, Spring MVC, Bean Validation, Spring Security.
- Spring JDBC with explicit SQL; PostgreSQL 17; Flyway migrations. One Maven project using Maven Wrapper.
- JUnit Jupiter, AssertJ, Testcontainers; GitHub Actions CI.
- Docker Compose: application, PostgreSQL, simulated bank. No frontend. Optional hosting: always-running Render Docker service and managed PostgreSQL.

## 3. Folder structure

```text
src/main/java/dev/sakshi/milestoneledger/
  receivables/ payments/ reconciliation/ exceptions/ audit/ security/ shared/
src/main/resources/db/migration/
src/test/java/dev/sakshi/milestoneledger/
demo/                         # API collection, simulator, scripts
.github/workflows/ci.yml
```

- Organize by capability; use `api/`, `application/`, `domain/`, `persistence/`, and worker/adapter packages where needed.
- Controllers handle HTTP; application services own transactions; domain code owns rules; repositories own SQL.
- Call other modules through explicit service APIs, never their repositories. Keep dependencies acyclic and `shared` small.

## 4. Coding standards

- Use descriptive names: `UpperCamelCase` types, `lowerCamelCase` members, lowercase packages. Follow the repository formatter; avoid unrelated reformatting.
- Apply DRY to business rules, especially allocation and idempotency. Do not abstract coincidentally similar code.
- Apply SOLID pragmatically: focused classes, narrow interfaces, preserved implementation contracts, constructor injection, composition over inheritance. Add extension points only for demonstrated variation; no interface per class or generic service framework.
- Prefer immutable values and record DTOs. Keep domain rules independent of HTTP. Inject `Clock`; use UTC timestamps.
- Use typed failures and the API error envelope. Never swallow exceptions or return success after failed writes.
- Store money as integer paise (`long`/`BIGINT`) with checked arithmetic; JSON amounts are strings. No floating point or independently writable balances.
- Enforce receipt identity by unique `(source, bank_receipt_id)`, not amount or delivery event ID. Changed reuse is a conflict; identical reuse is a no-op.
- Lock receipt before demand and recompute balances under locks. Net allocations cannot exceed either available receipt funds or demand amount. Preserve residual funds with an explanation.
- Commit receipt creation, ledger entries, automatic allocation, exception changes, and inbox completion atomically. Persist inbox acceptance before acknowledging delivery.
- Use service-level transactions through Spring proxies or explicit transaction templates; never rely on self-invoked `@Transactional`. Ensure all failed money operations roll back, including checked exceptions. [Spring transaction semantics](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- Keep network calls outside database transactions. Use bounded workers and durable retries. Reconciliation must use the same ingestion rules and never infer absence from incomplete bank data.
- Posted financial facts are immutable; correct allocations through linked reversals. Use parameterized SQL and new versioned migrations; never rewrite applied migrations.

## 5. Testing rules

- Test behavior and invariants, not getters or mocked implementation details. Add regression tests for defects.
- Use real PostgreSQL through Testcontainers for transactions, constraints, and concurrency; never substitute H2 or repository mocks for these guarantees.
- Cover duplicate/concurrent delivery, equal-value distinct receipts, competing allocations, excess funds, reversal, authorization, signature failures, reconciliation races, and partial bank reads.
- Crash tests must restart the application against the same database. Do not let test-managed transactions hide real commit behavior.
- Use deterministic fixtures, synchronization barriers, and bounded polling; no arbitrary sleeps or live services.
- Run relevant tests during development and `./mvnw verify` before code handoff once scaffolded. Wire integration tests into `verify`; report unavailable checks honestly.

## 6. Security and logging rules

- Enforce `CERTIFIER`, `ACCOUNTS`, and `MANAGER` permissions at application-service boundaries; manager does not automatically imply certifier.
- Use configured BCrypt hashes and Basic authentication for the controlled API demo; HTTPS when hosted. Never hardcode credentials or disable security globally.
- Verify webhook HMAC against the timestamp and raw body, using constant-time comparison and replay tolerance. Derive project/account scope from trusted configuration.
- Validate inputs and resource scope. Keep secrets outside Git; commit placeholder configuration only. Do not assume Basic authentication removes CSRF risk.
- Use synthetic data and least-privilege database access; separate migration credentials from runtime credentials.
- Log structured JSON to stdout with internal IDs, outcomes, timings, and retries. Exclude payloads, bank details, credentials, and signatures. Persist business audit history in PostgreSQL.

## 7. Dependency rules

- Use Spring Boot dependency management; pin unmanaged libraries/plugins and container versions. No snapshots or `latest` tags. [Spring build guidance](https://docs.spring.io/spring-boot/reference/using/build-systems.html)
- Prefer the JDK and existing dependencies. Justify new libraries, verify compatibility and maintenance, and remove unused dependencies.
- No ORM, Redis, broker, reactive stack, or distributed infrastructure without an approved architectural need.

## 8. Git/commit rules

- Inspect status first; preserve existing user changes. Stage only task-related files; exclude secrets, build output, and machine-specific files.
- Use `codex/` for new branches unless directed otherwise. Keep commits focused, with imperative messages such as `fix: prevent concurrent over-allocation`.
- Review the diff and run applicable checks. Do not commit or push unless requested; never amend others' commits, force-push, or discard changes without explicit authorization.

## 9. Never change without asking

Unless already explicitly authorized, ask before:

- Renaming the product, replacing the stack, splitting services, or adding frontend/real integrations.
- Breaking the API, changing money units, identity keys, allocation invariants, role permissions, or audit retention.
- Destructive schema/data changes, weakening security or CI checks, or removing correctness tests.
- Deploying publicly, creating paid resources, or adding the optional outbound outbox to scope.

Routine fixes, additive migrations within agreed requirements, and relevant tests do not need renewed permission. Never bypass a failing safeguard just to make checks pass.

## 10. Definition of done

- Requested behavior meets the applicable acceptance criteria; no unrelated features or unfinished placeholders.
- Relevant tests and configured verification pass; disclose every skipped or blocked check.
- Money invariants, authorization, audit history, and restart/retry behavior remain intact.
- Update affected contracts, migrations, configuration examples, and demo instructions. Verify Docker startup when runtime/setup changes.
- Review the diff for scope and secrets. Report what changed, validation performed, and remaining limitations. Documentation-only edits need document checks, not application tests.
