# Milestone Ledger

Milestone Ledger is an API-first collections backend for certified infrastructure-project milestones. It helps an accounts executive answer three questions: what is due, what money arrived, and which differences need investigation.

This is a synthetic-data portfolio prototype inspired by a contractor workflow. It is not a production billing platform and does not represent Eagle Infra's internal systems.

## Current status

Day 3 adds a database-backed receipt worker to the certified-demand slice. Signed deliveries are durably accepted, then processed with immutable receipt/financial-entry facts, exact-reference allocation, visible residual/conflict exceptions, duplicate receipt protection, and retryable failure metadata.

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

The demo certifies one scheduled ₹1,00,000 milestone, then sends signed synthetic notifications and polls each inbox event to a terminal state. It asserts a ₹40,000 partial payment, five deliveries with one financial effect, a final receipt that settles the demand with ₹10,000 excess, two distinct equal-value receipts, missing/unknown references, and a changed bank record that creates a conflict without altering posted money. Reconciliation and manual allocation remain out of scope.

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

Supply raw credentials and the webhook signing secret from your shell, then run the persisted-response demo. It requires `jq`, `curl`, and `openssl`; the script contains no credential or signing value. It checks setup/authentication, certification replay, signed receipt processing, derived balances, financial history, and visible exceptions. Run it against a fresh synthetic database with a scheduled milestone.

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

The integration tests start isolated PostgreSQL 17 containers, apply migrations with the owner identity, run the application with the restricted runtime identity, and cover Day 1/2 regressions, V5-to-Day-3 upgrade, signed webhook acceptance, processing/locking, rollback, retry, and operational APIs.

## Latest verification evidence

On 2026-09-19, `./mvnw verify` passed locally with PostgreSQL 17 Testcontainers (35 tests, no failures/errors). The isolated Compose project `milestone_ledger_day3_verify` built and started on alternate localhost ports without touching developer volumes; `demo/setup-read.sh` passed with externally supplied synthetic credentials. Restarting only its app container preserved the settled demand and open exceptions. A second signed event was deliberately paused inside a financial-entry transaction; killing the isolated app left its inbox row `PENDING` with no receipt link, and restarting processed it into one receipt entry. The temporary trigger was removed afterward.
