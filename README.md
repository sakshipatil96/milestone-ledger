# Milestone Ledger

Milestone Ledger is an API-first collections backend for certified infrastructure-project milestones. It helps an accounts executive answer three questions: what is due, what money arrived, and which differences need investigation.

This is a synthetic-data portfolio prototype inspired by a contractor workflow. It is not a production billing platform and does not represent Eagle Infra's internal systems.

## Current status

Day 1 provides read-only inspection of one seeded project and its two milestones. It uses separate migration/runtime database identities, BCrypt-configured HTTP Basic demo identities, correlated errors, and PostgreSQL-backed readiness.

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

## MVP proof

The completed demo will show that a receipt delivered five times changes a demand balance once, while two genuine same-amount receipts both remain recorded. It will also show reconciliation recovering a receipt whose notification was omitted.

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

The integration test starts an isolated PostgreSQL 17 container, applies every migration with the owner identity, runs the application with the restricted runtime identity, and verifies setup APIs, authorization, validation helpers, fixture preservation, and denied setup writes.

## Latest verification evidence

The latest local verification passed `./mvnw verify`: `AppSecurityPropertiesTest` covers valid and malformed BCrypt configuration; `JsonAccessDeniedHandlerTest` covers the HTTP `403` correlated envelope; `CorrelationIdFilterTest` covers successful-health log suppression; and `DatabaseMigrationIT` covers restricted runtime writes, fixture re-migration preservation, all three setup-read roles, request/error correlation, project isolation, malformed cursor handling, stable paginated milestone pages including an empty page, service authorization, and money/reference validation. An isolated Compose run also passed the setup demo, restart preservation for both project and milestone responses, and database outage behavior (`ready=503`, `live=200`, protected setup read `503`). The demo's macOS Bash compatibility and nested-client-ID parsing were corrected during that run.
