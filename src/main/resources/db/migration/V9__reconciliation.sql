CREATE TABLE reconciliation_run (
    id uuid PRIMARY KEY,
    source varchar(50) NOT NULL,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    account_reference varchar(100) NOT NULL,
    actor_id uuid NOT NULL REFERENCES app_actor(id) ON DELETE RESTRICT,
    request_id varchar(100) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'QUEUED' CHECK (status IN
        ('QUEUED','RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED')),
    phase varchar(20) NOT NULL DEFAULT 'FETCH' CHECK (phase IN ('FETCH','COMPARE','ABSENCE','WAIT','DONE')),
    snapshot_id varchar(200),
    as_of timestamptz,
    next_cursor varchar(500),
    lease_owner uuid,
    lease_version bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    fetch_attempt_count integer NOT NULL DEFAULT 0,
    page_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    bank_count integer NOT NULL DEFAULT 0,
    recovered_count integer NOT NULL DEFAULT 0,
    conflict_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz
);
CREATE UNIQUE INDEX reconciliation_one_active_idx ON reconciliation_run(source, project_id)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX reconciliation_due_idx ON reconciliation_run(next_attempt_at, created_at)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX reconciliation_project_recent_idx ON reconciliation_run(project_id, created_at DESC);

CREATE TABLE reconciliation_cursor (
    run_id uuid NOT NULL REFERENCES reconciliation_run(id) ON DELETE RESTRICT,
    cursor_value varchar(500) NOT NULL,
    PRIMARY KEY (run_id, cursor_value)
);

CREATE TABLE reconciliation_item (
    run_id uuid NOT NULL REFERENCES reconciliation_run(id) ON DELETE RESTRICT,
    bank_receipt_id varchar(200) NOT NULL,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    currency char(3) NOT NULL CHECK (currency='INR'),
    demand_reference varchar(100),
    posted_at timestamptz NOT NULL,
    fact_hash char(64) NOT NULL,
    inbox_event_id uuid REFERENCES inbox_event(id) ON DELETE RESTRICT,
    outcome varchar(20) NOT NULL DEFAULT 'STAGED' CHECK (outcome IN
        ('STAGED','MATCHED','ENQUEUED','CONFLICT','PROCESSED','FAILED')),
    PRIMARY KEY (run_id, bank_receipt_id)
);
CREATE INDEX reconciliation_item_outcome_idx ON reconciliation_item(run_id, outcome);

CREATE TABLE reconciliation_exception (
    run_id uuid NOT NULL REFERENCES reconciliation_run(id) ON DELETE RESTRICT,
    exception_id uuid NOT NULL REFERENCES exception_case(id) ON DELETE RESTRICT,
    PRIMARY KEY (run_id, exception_id)
);

ALTER TABLE exception_case DROP CONSTRAINT exception_case_type_check;
ALTER TABLE exception_case ADD CONSTRAINT exception_case_type_check CHECK
    (type IN ('UNALLOCATED_FUNDS','BANK_RECORD_CONFLICT','LOCAL_RECEIPT_NOT_IN_BANK'));

GRANT SELECT, INSERT, UPDATE (status, phase, snapshot_id, as_of, next_cursor, lease_owner,
    lease_version, lease_until, fetch_attempt_count, page_count, next_attempt_at, bank_count,
    recovered_count, conflict_count, failed_count, error_code, started_at, finished_at)
    ON reconciliation_run TO milestone_app;
GRANT SELECT, INSERT ON reconciliation_cursor TO milestone_app;
GRANT SELECT, INSERT, UPDATE (inbox_event_id, outcome) ON reconciliation_item TO milestone_app;
GRANT SELECT, INSERT ON reconciliation_exception TO milestone_app;
