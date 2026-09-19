# Milestone Ledger

Milestone Ledger is an API-first collections backend for certified infrastructure-project milestones. It helps an accounts executive answer three questions: what is due, what money arrived, and which differences need investigation.

This is a synthetic-data portfolio prototype inspired by a contractor workflow. It is not a production billing platform and does not represent Eagle Infra's internal systems.

## Current status

Day 2 completes certification and demand creation as a transactionally verified vertical slice. It uses separate migration/runtime database identities, BCrypt-configured HTTP Basic demo identities, CSRF-protected browser-style mutations, correlated errors, PostgreSQL-backed readiness, and signed durable bank-event acceptance. Receipt processing is intentionally still in progress: accepted events remain `PENDING`.

## Planned stack

- Java 21, Spring Boot, Spring JDBC, Flyway, Maven
- PostgreSQL 17
- Docker Compose, Testcontainers, GitHub Actions
- Simulated bank webhook and reconciliation feed
- API demo collection; no frontend in the MVP

## Product documents

- [Product requirements](PRD.md)
- [System design](SYSTEM_DESIGN.md)
- [Data model](DATA_MODEL.md)
- [API contract](API_CONTRACT.md)

AI guidance and working checklists are intentionally local-only and excluded from Git.

## Current proof and boundary

The Day 2 demo certifies one scheduled ₹1,00,000 milestone, creates one `OPEN` demand, proves idempotent replay and authorization failures, then signs and stores a fictional bank notification as `PENDING`. It does not process receipts, allocate money, or run reconciliation; those are Day 3 features.

## Scope boundaries

The MVP supports one synthetic project, one synthetic client, INR, approved net collectible amounts, exact-reference matching, explicit exceptions, and reconciliation. It excludes real bank connections, payment initiation, GST/TDS calculations, certification approval workflows, and client data.

## Run locally

Prerequisites: Java 21 and a running Docker Desktop installation.

```bash
cp .env.example .env
docker compose up --build
```

The copied values are local synthetic placeholders. Replace them in `.env` if desired; never commit that file.

Generate three BCrypt hashes locally (for example `htpasswd -bnBC 12 '' 'a-local-password' | tr -d ':\n'`) and put them in `.env` as the three `APP_SECURITY_*_PASSWORD_HASH` values. Set `APP_SETUP_PROJECT_ID` to the supplied synthetic project UUID. Keep the three raw passwords only in your shell when using the demo; never put them in the script or commit them.

Once the containers are healthy:

- Application readiness: `http://localhost:8080/actuator/health/readiness`
- Application liveness: `http://localhost:8080/actuator/health/liveness`
- Simulated bank receipts: `http://localhost:8089/receipts`
- Day 1 liveness: `http://localhost:8080/api/v1/health/live`
- Day 1 readiness: `http://localhost:8080/api/v1/health/ready`

All Compose-published ports bind to `127.0.0.1`. The legacy actuator probes and `/livez`/`/readyz` remain available for compatibility.

## Setup demo

Supply raw credentials from your shell, then run the persisted-response demo. It checks readiness, reads projects and milestones for certifier/accounts/manager, and confirms missing and invalid credentials return `401`.

```bash
export CERTIFIER_PASSWORD='...'
export ACCOUNTS_PASSWORD='...'
export MANAGER_PASSWORD='...'
export BANK_WEBHOOK_SIGNING_SECRET='...'
./demo/setup-read.sh
```

Stop the environment without deleting its database volume:

```bash
docker compose down
```

Do not use `docker compose down -v`: the named volume intentionally preserves fixtures and any later test records. Re-running the app applies Flyway safely and never re-runs its deterministic seed migration.

## Run verification

The Maven Wrapper runs unit tests and PostgreSQL integration tests. Docker Desktop must be running.

```bash
./mvnw verify
```

The integration tests start isolated PostgreSQL 17 containers, apply every migration with the owner identity, run the application with the restricted runtime identity, and cover setup regression behavior plus CSRF-protected certification/idempotency and signed pending-webhook acceptance.

## Latest verification evidence

Latest verification passed `./mvnw verify` with PostgreSQL 17 Testcontainers: `AppSecurityPropertiesTest`, `JsonAccessDeniedHandlerTest`, `CorrelationIdFilterTest`, `ApiExceptionHandlerTest`, `BankWebhookServiceTest`, `DatabaseMigrationIT`, and `CertificationAndIngestionIT` (19 tests total). The integration suite applies V1–V5 to clean databases, safely re-applies migrations to an existing Day 1 fixture, and verifies CSRF, certification/idempotency, real PostgreSQL concurrency, runtime immutability, transaction rollback at certification/demand/audit boundaries, HMAC acceptance/replay/conflict, malformed and concurrent webhook handling, database-unavailable refusal, and pending-event persistence through a fresh application context. The credential-dependent Compose demo was also run successfully with local external credentials. Do not treat receipt processing as verified until its Day 3 combined gate is complete.
