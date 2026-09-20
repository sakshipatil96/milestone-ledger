-- Day 3 keeps accepted delivery facts intact while allowing notifications without a reference.
ALTER TABLE inbox_event ALTER COLUMN demand_reference DROP NOT NULL;
ALTER TABLE inbox_event ADD CONSTRAINT inbox_event_reference_when_present
    CHECK (demand_reference IS NULL OR (demand_reference = btrim(demand_reference) AND demand_reference <> ''));
ALTER TABLE inbox_event ADD COLUMN receipt_id uuid;
ALTER TABLE inbox_event ADD COLUMN origin varchar(20) NOT NULL DEFAULT 'WEBHOOK'
    CHECK (origin IN ('WEBHOOK', 'RECOVERY'));

CREATE TABLE receipt (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source varchar(50) NOT NULL CHECK (source = btrim(source) AND source <> ''),
    bank_receipt_id varchar(200) NOT NULL CHECK (bank_receipt_id = btrim(bank_receipt_id) AND bank_receipt_id <> ''),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    currency char(3) NOT NULL CHECK (currency = 'INR'),
    demand_reference varchar(100) CHECK (demand_reference IS NULL OR (demand_reference = btrim(demand_reference) AND demand_reference <> '')),
    posted_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    fact_hash char(64) NOT NULL,
    CONSTRAINT receipt_source_bank_id_unique UNIQUE (source, bank_receipt_id)
);
ALTER TABLE inbox_event ADD CONSTRAINT inbox_event_receipt_fk FOREIGN KEY (receipt_id) REFERENCES receipt(id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION prevent_receipt_fact_changes()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.source, NEW.bank_receipt_id, NEW.project_id, NEW.amount_paise, NEW.currency,
        NEW.demand_reference, NEW.posted_at, NEW.recorded_at, NEW.fact_hash)
       IS DISTINCT FROM
       (OLD.id, OLD.source, OLD.bank_receipt_id, OLD.project_id, OLD.amount_paise, OLD.currency,
        OLD.demand_reference, OLD.posted_at, OLD.recorded_at, OLD.fact_hash) THEN
        RAISE EXCEPTION 'posted receipt facts are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER receipt_facts_immutable BEFORE UPDATE ON receipt
    FOR EACH ROW EXECUTE FUNCTION prevent_receipt_fact_changes();

CREATE TABLE financial_entry (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind varchar(30) NOT NULL CHECK (kind IN ('RECEIPT', 'ALLOCATION', 'ALLOCATION_REVERSAL')),
    receipt_id uuid NOT NULL REFERENCES receipt(id) ON DELETE RESTRICT,
    demand_id uuid REFERENCES demand(id) ON DELETE RESTRICT,
    amount_paise bigint NOT NULL,
    reverses_entry_id uuid REFERENCES financial_entry(id) ON DELETE RESTRICT,
    actor_id uuid NOT NULL REFERENCES app_actor(id) ON DELETE RESTRICT,
    reason varchar(500) NOT NULL CHECK (reason = btrim(reason) AND reason <> ''),
    inbox_event_id uuid REFERENCES inbox_event(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT financial_entry_shape CHECK (
        (kind = 'RECEIPT' AND amount_paise > 0 AND demand_id IS NULL AND reverses_entry_id IS NULL)
        OR (kind = 'ALLOCATION' AND amount_paise > 0 AND demand_id IS NOT NULL AND reverses_entry_id IS NULL)
        OR (kind = 'ALLOCATION_REVERSAL' AND amount_paise < 0 AND demand_id IS NOT NULL AND reverses_entry_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX financial_entry_one_receipt_idx ON financial_entry(receipt_id) WHERE kind = 'RECEIPT';
CREATE UNIQUE INDEX financial_entry_one_reversal_idx ON financial_entry(reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX financial_entry_receipt_time_idx ON financial_entry(receipt_id, created_at, id);
CREATE INDEX financial_entry_demand_time_idx ON financial_entry(demand_id, created_at, id);

CREATE TABLE exception_case (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type varchar(40) NOT NULL CHECK (type IN ('UNALLOCATED_FUNDS', 'BANK_RECORD_CONFLICT')),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    receipt_id uuid REFERENCES receipt(id) ON DELETE RESTRICT,
    source varchar(50),
    bank_receipt_id varchar(200),
    dedupe_key varchar(300) NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    reason_code varchar(80) NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    CONSTRAINT exception_identity CHECK ((receipt_id IS NOT NULL) OR (source IS NOT NULL AND bank_receipt_id IS NOT NULL))
);
CREATE INDEX exception_case_project_status_idx ON exception_case(project_id, status, first_seen_at, id);

GRANT SELECT, INSERT, UPDATE (receipt_id, status, attempt_count, next_attempt_at, last_error_code, processed_at) ON inbox_event TO milestone_app;
GRANT SELECT, INSERT, UPDATE (id) ON receipt TO milestone_app;
GRANT SELECT, INSERT ON financial_entry TO milestone_app;
GRANT SELECT, INSERT, UPDATE (status, reason_code, last_seen_at, resolved_at) ON exception_case TO milestone_app;
REVOKE UPDATE, DELETE ON financial_entry FROM milestone_app;
REVOKE DELETE ON receipt, exception_case FROM milestone_app;
