# Local operations

This runbook applies to the synthetic, locally bound MVP. Keep credentials in environment variables or a local ignored `.env`; never paste signatures, bank payloads, or password hashes into tickets or logs. All API errors carry `X-Request-Id` and a safe `error.requestId`.

## Failed ingestion

1. As manager, inspect `GET /api/v1/ingestion-events?status=FAILED` and then the event ID. Check its `lastErrorCode`, attempt count, and related receipt ID. Inspect the corresponding receipt, exception, and financial-entry APIs before taking action.
2. Correct only the external condition. A manager can call `POST /api/v1/ingestion-events/{eventId}/retry` with a reason, `Idempotency-Key`, and the Basic-auth CSRF cookie/header flow described in `API_CONTRACT.md`. Retry is accepted only for a failed event. Receipt uniqueness prevents duplicate inflow; the event may end in `CONFLICT` if its facts disagree with a posted receipt.
3. Poll that event to `PROCESSED`, `CONFLICT`, or `FAILED`; inspect exceptions and balances. Retrying an event linked to an already finished reconciliation run does not rewrite that run's historical counts or failed event IDs.

## Failed or incomplete reconciliation

1. Read the latest `reconciliation` object on a demand/receipt worklist, then `GET /api/v1/reconciliation-runs/{runId}`. `QUEUED`/`RUNNING` counts are provisional. `FAILED` with a fetch code means comparison of a complete snapshot did not happen; staged `bankCount` is partial. `COMPLETED_WITH_ERRORS` means the bank comparison finished but one or more linked recovery events failed. Open exceptions can remain after `COMPLETED`.
2. For a transient bank outage, the worker persists up to five attempts with 1/2/4/8-second delays per page. It resumes from the last committed cursor. A changed snapshot ID/`asOf`, cursor loop, conflicting repeated identity, malformed record, or HTTP 410 expires the run immediately. Do not interpret an incomplete run as evidence that local receipts are missing from the bank.
3. After correcting the simulator/bank condition, create a **new** run with a new idempotency key. A failed run is historical and is not reset. For `COMPLETED_WITH_ERRORS`, inspect `failedEventIds`, retry failed ingestion as above if appropriate, and start a later reconciliation to establish a new complete result.

## Restart and recovery

Use `docker compose restart app` without deleting the PostgreSQL volume. Readiness checks database connectivity only; poll the specific inbox event or reconciliation run for actual progress. A pending inbox event remains eligible after a restart. An interrupted reconciliation step rolls back; its 15-second lease expires and a worker resumes from the committed phase and cursor. The stable simulator fixture must remain available under the same snapshot ID and `asOf`; if it does not, the run fails visibly and a new run is required. The isolated Day 5 demo verified persisted completed run/receipt reads after an app restart. Automated tests started new application instances after a committed page, during comparison/enqueue, and while awaiting recovery events, and verified expired lease takeover and stale-worker rejection. An actual process kill mid-fetch has not been separately exercised.

## Backup and restore

Back up PostgreSQL before operational changes. For a local Compose instance, `docker compose exec -T postgres pg_dump -U milestone_owner -Fc milestone_ledger > backup.dump` creates a logical backup in the current directory. Restrict the file's permissions and store it outside source control. Restore into a **fresh isolated database** with `pg_restore` using the owner role, then run the application/Flyway against that database and verify representative demand, receipt, financial entry, inbox, exception, note, and reconciliation IDs through scoped reads. Never restore over the active developer volume. This backup/restore procedure is documented but has not been executed as part of Day 5 verification.

## Monitoring gaps

Logs include safe run/event IDs, committed page and terminal outcomes, retry count, duration, and error codes. The API exposes pending/failed ingestion counts and reconciliation freshness. There is no alert delivery, metrics dashboard, bank snapshot age alarm, or production bank integration. A healthy database or readiness probe does not establish that workers are making progress. The simulator admin API is bound to localhost by Compose; keep it inaccessible outside the local demo.
