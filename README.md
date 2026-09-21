# Milestone Ledger

Milestone Ledger is an API-first collections backend for certified infrastructure-project milestones. It helps an accounts executive answer three questions: what is due, what money arrived, and which differences need investigation.

This is a synthetic-data portfolio prototype inspired by a contractor workflow. It is not a production billing platform and does not represent Eagle Infra's internal systems.

## Current status

The five-day API-only MVP is complete. Day 5 adds durable bank-snapshot reconciliation and a reproducible proof flow to the certified-demand and collections slice. Signed deliveries and trusted recovery events share the inbox path; receipts, financial entries, and investigation notes remain append-only. The pushed application revision passed local and GitHub Actions verification.

## Stack

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

The proof certifies one scheduled ₹1,00,000 milestone, records a ₹40,000 receipt, appends a note, and allocates ₹15,000 then ₹25,000. It then shows five deliveries with one receipt effect, two distinct equal-value receipts, and a missed ₹60,000 notification recovered from a bank snapshot. The remaining demand becomes settled. A repeat run creates no duplicate money; changed bank facts and a local-only receipt remain visible as discrepancies; an unavailable page ends in a visible failed run. Reversal remains deferred.

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

The Maven Wrapper runs unit tests and PostgreSQL integration tests. Docker Desktop must be running and accessible. CI uses the same command on Java 21 and retains test reports on failure; Docker-backed tests fail the job if Docker is unavailable.

```bash
./mvnw verify
```

The integration tests start isolated PostgreSQL 17 containers, apply migrations with the owner identity, run the application with the restricted runtime identity, and cover V8-through-V10 upgrade, signed webhook acceptance, processing/locking, rollback, retry, reconciliation recovery, exceptions, failed snapshots, and operational APIs.

## Five-minute proof

Run this against Docker Desktop from a fresh checkout. The runner creates disposable synthetic credentials and an isolated Compose project on alternate localhost ports. It does not use or remove the developer's usual Compose volume. Build and health startup are excluded from the timed proof; the runner asserts the timed portion stays within five minutes, then restarts the app and re-reads persisted run and receipt IDs.

```bash
bash demo/run-day5-compose.sh
```

The earlier under-two-minute collections inspection remains available through `bash demo/run-day4-compose.sh`. The Day 5 runner reuses that flow, then adds reconciliation checks. Its bank controls are WireMock admin mappings on the local simulator, not product endpoints. The default static snapshot is deliberately empty; the demo installs synthetic mappings while it runs and removes them afterward.

## Operations and limitations

See [operations](OPERATIONS.md) for failed ingestion, reconciliation, restart, and backup/restore procedures. Reconciliation depends on a stable, complete simulated snapshot through its `asOf`; it cannot prove bank completeness outside that simulator contract. The MVP has one configured project/source/account, INR only, no real bank integration, no allocation reversal, no outbound outbox, and no public deployment. Readiness checks database health, while reconciliation progress and errors come from the run API and worklist metadata.

Build and Compose images are pinned by locally resolved SHA-256 digests; GitHub Actions are pinned to full commit SHAs. Maven dependency versions resolve through the pinned Spring Boot parent and Maven Wrapper. `APP_VERSION` is the GitHub revision in CI test logs; the isolated local demo labels its working tree separately.

## Latest verification evidence

On 2026-09-20, after the structured-log safety fix, local `./mvnw --batch-mode verify` passed **76 tests, zero failures/errors/skips**. Its failure-path tests emitted 863 structured log records with no exception metadata or stack traces; a dedicated regression test verifies that throwable details are omitted while the safe log message remains. A fresh isolated `bash demo/run-day5-compose.sh` passed the timed proof in **30 seconds**, excluding build/startup, and verified persisted run and receipt reads after app restart. The demo output contained none of the selected credential, signature-header, or stack-trace patterns checked.

Earlier on 2026-09-20, local `./mvnw verify -q` passed **75 tests, zero failures/errors/skips** against pinned PostgreSQL 17.6 Testcontainers. `ReconciliationIT` covers omitted receipt recovery, repeated runs, webhook precedence, changed and local-only facts, complete two-page staging, malformed/looping/inconsistent/expired/oversized snapshot handling, database-unsafe text, five fetch attempts, failed recovery events, historical counts, role/CSRF/scope/idempotency, concurrent starts, lease takeover/stale write rejection, a conflict observed after snapshot `asOf`, manager retry during finalization, bounded absence batches, and new application instances resuming after page commit, during comparison, and while awaiting recovery. `DatabaseMigrationIT` verifies fresh migrations and V8 upgrade with existing pending event, demand, receipt, receipt/allocation entries, exception, and investigation note. Testcontainers fails the suite when Docker is unavailable.

`bash demo/run-day5-compose.sh` also passed on an earlier fresh isolated Compose project with V10 and pinned images. That timed proof took **28 seconds**, excluding build/startup; it verified the Day 4 inspection/allocation flow, five duplicate deliveries, two equal-value distinct receipts, missed-notification recovery, repeat safety, changed facts, local-only discrepancy, unavailable snapshot page, and persisted run/receipt reads after an app restart. Its synthetic named volume was retained; no developer volume was touched.

Performance method: on a local **Darwin arm64** host with **Docker Desktop 29.6.1**, PostgreSQL 17.6, and Java 21, `ReconciliationIT.measureThousandReceiptWorklistAndNormalProcessing` inserted 1,000 receipts with receipt entries, warmed the API, then timed ten authenticated page-100 worklist requests. The final run measured **127–150 ms**, nearest-rank p95 **150 ms** against the under-one-second target. One normal receipt processing transaction took **6 ms** against the five-second target. These are local observations, not production capacity claims. The application code was committed in `c2841642bae928129c73f8f0af6140d68df3fdb8`; [GitHub Actions run 35548919600](https://github.com/sakshipatil96/milestone-ledger/actions/runs/35548919600) passed its Java 21 `./mvnw verify` job with 75 tests, zero failures/errors/skips. Its workflow log was checked for selected credential and sensitive-header patterns, with no matches.

On 2026-09-20, `./mvnw verify -q` passed locally with PostgreSQL 17 Testcontainers (50 tests, no failures/errors). Coverage includes V7→V8 financial-record preservation, worklist filters and tied-timestamp cursors, a PostgreSQL-synchronized single-response snapshot, pending/failed ingestion without phantom money, empty-versus-missing history, no-receipt residuals, boundary and project-scope errors, and a real worklist dependency failure. Successful collection reads are quiet; failed reads retain correlated status and timing without logging query strings or credentials. Manual-allocation races, rollback, persisted notes, role checks, and replay after application restart are also covered.

Run `bash demo/run-day4-compose.sh` for a fresh isolated Day 4 demo on alternate localhost ports. The runner creates disposable credentials in its process environment, builds and starts Compose, checks the inspection/note/₹15,000 plus ₹25,000 allocation/replay/history flow, then stops the containers. Its named synthetic PostgreSQL volume remains available for inspection. This isolated run passed with persisted demand `e370a22f-d1db-4a87-91d8-8b7ec995512e`, receipt `2bf5baac-8b48-4c19-93ce-6d1168a39029`, and exception `b136108f-9b55-474a-b700-fafb5cea2c76`. The prior isolated Compose Day 3 evidence remains recorded below.

The isolated Day 4 Compose flow was rerun after the logging change on 2026-09-20 and passed in 22.5 seconds; its containers were stopped and its synthetic named volume was retained.
