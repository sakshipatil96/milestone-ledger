-- Advance the local-receipt absence scan in bounded, restartable batches.
ALTER TABLE reconciliation_run ADD COLUMN absence_cursor uuid;
GRANT UPDATE (absence_cursor) ON reconciliation_run TO milestone_app;
