# Milestone Ledger

Milestone Ledger is an API-first collections backend for certified infrastructure-project milestones. It helps an accounts executive answer three questions: what is due, what money arrived, and which differences need investigation.

This is a synthetic-data portfolio prototype inspired by a contractor workflow. It is not a production billing platform and does not represent Eagle Infra's internal systems.

## Current status

Day 4 adds collections worklists, ingestion-lag visibility, append-only investigation notes, and idempotent manual allocation to the certified-demand slice. Signed deliveries remain durably accepted and receipts/financial entries remain immutable.

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

The demo certifies one scheduled ₹1,00,000 milestone, then sends signed synthetic notifications and polls each inbox event to a terminal state. Use `GET /api/v1/demands?status=OPEN&status=PARTIALLY_PAID`, `GET /api/v1/receipts`, and `GET /api/v1/exceptions` to inspect outstanding money, received money, lag, and exceptions. Accounts or a manager can append a note and allocate an unmatched receipt with CSRF plus an idempotency key. A ₹40,000 receipt allocated first for ₹15,000 remains an open residual; allocating the final ₹25,000 resolves that residual and leaves the demand outstanding at ₹60,000. Reversal and reconciliation remain out of scope.

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

On 2026-09-20, `./mvnw verify -q` passed locally with PostgreSQL 17 Testcontainers (50 tests, no failures/errors). Coverage includes V7→V8 financial-record preservation, worklist filters and tied-timestamp cursors, a PostgreSQL-synchronized single-response snapshot, pending/failed ingestion without phantom money, empty-versus-missing history, no-receipt residuals, boundary and project-scope errors, and a real worklist dependency failure. Successful collection reads are quiet; failed reads retain correlated status and timing without logging query strings or credentials. Manual-allocation races, rollback, persisted notes, role checks, and replay after application restart are also covered.

Run `bash demo/run-day4-compose.sh` for a fresh isolated Day 4 demo on alternate localhost ports. The runner creates disposable credentials in its process environment, builds and starts Compose, checks the inspection/note/₹15,000 plus ₹25,000 allocation/replay/history flow, then stops the containers. Its named synthetic PostgreSQL volume remains available for inspection. This isolated run passed with persisted demand `e370a22f-d1db-4a87-91d8-8b7ec995512e`, receipt `2bf5baac-8b48-4c19-93ce-6d1168a39029`, and exception `b136108f-9b55-474a-b700-fafb5cea2c76`. The prior isolated Compose Day 3 evidence remains recorded below.

The isolated Day 4 Compose flow was rerun after the logging change on 2026-09-20 and passed in 22.5 seconds; its containers were stopped and its synthetic named volume was retained.
