-- Certification facts are posted once.  The runtime role may only make the
-- initial all-null -> fully-populated transition; migrations retain ownership.
CREATE OR REPLACE FUNCTION prevent_certification_fact_changes()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.certified_at IS NOT NULL
       AND (NEW.certified_amount_paise, NEW.certification_reference, NEW.certified_at, NEW.certified_by)
           IS DISTINCT FROM
           (OLD.certified_amount_paise, OLD.certification_reference, OLD.certified_at, OLD.certified_by) THEN
        RAISE EXCEPTION 'posted certification facts are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER milestone_certification_facts_immutable
    BEFORE UPDATE ON milestone
    FOR EACH ROW EXECUTE FUNCTION prevent_certification_fact_changes();

REVOKE UPDATE ON milestone FROM milestone_app;
GRANT UPDATE (certified_amount_paise, certification_reference, certified_at, certified_by) ON milestone TO milestone_app;

CREATE TABLE inbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source varchar(50) NOT NULL CHECK (source = btrim(source) AND source <> ''),
    event_id varchar(200) NOT NULL CHECK (event_id = btrim(event_id) AND event_id <> ''),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    account_reference varchar(100) NOT NULL CHECK (account_reference = btrim(account_reference) AND account_reference <> ''),
    bank_receipt_id varchar(200) NOT NULL CHECK (bank_receipt_id = btrim(bank_receipt_id) AND bank_receipt_id <> ''),
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    currency char(3) NOT NULL CHECK (currency = 'INR'),
    demand_reference varchar(100) NOT NULL CHECK (demand_reference = btrim(demand_reference) AND demand_reference <> ''),
    posted_at timestamptz NOT NULL,
    canonical_hash char(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSED', 'CONFLICT', 'FAILED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error_code varchar(100),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    CONSTRAINT inbox_event_source_event_unique UNIQUE (source, event_id)
);

CREATE INDEX inbox_event_pending_due_idx
    ON inbox_event (next_attempt_at, received_at, id) WHERE status = 'PENDING';
CREATE INDEX inbox_event_project_status_idx
    ON inbox_event (project_id, status, received_at, id);

GRANT SELECT, INSERT ON inbox_event TO milestone_app;
