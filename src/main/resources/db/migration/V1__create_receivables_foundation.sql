CREATE TABLE app_actor (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    external_subject varchar(100) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    role varchar(30) NOT NULL CHECK (role IN ('CERTIFIER', 'ACCOUNTS', 'MANAGER', 'SYSTEM')),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE client (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id uuid NOT NULL REFERENCES client(id) ON DELETE RESTRICT,
    code varchar(50) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE milestone (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    name varchar(200) NOT NULL,
    certified_amount_paise bigint CHECK (certified_amount_paise > 0),
    certification_reference varchar(100),
    certified_at timestamptz,
    certified_by uuid REFERENCES app_actor(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT milestone_project_sequence_unique UNIQUE (project_id, sequence_number),
    CONSTRAINT milestone_id_project_unique UNIQUE (id, project_id),
    CONSTRAINT milestone_certification_all_or_none CHECK (
        (certified_amount_paise IS NULL
            AND certification_reference IS NULL
            AND certified_at IS NULL
            AND certified_by IS NULL)
        OR
        (certified_amount_paise IS NOT NULL
            AND certification_reference IS NOT NULL
            AND certified_at IS NOT NULL
            AND certified_by IS NOT NULL)
    ),
    CONSTRAINT milestone_reference_trimmed CHECK (
        certification_reference IS NULL OR certification_reference = btrim(certification_reference)
    )
);

CREATE UNIQUE INDEX milestone_certification_reference_unique
    ON milestone (certification_reference)
    WHERE certification_reference IS NOT NULL;

CREATE TABLE demand (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    milestone_id uuid NOT NULL,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    reference varchar(100) NOT NULL UNIQUE,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    due_date date,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT demand_one_per_milestone UNIQUE (milestone_id),
    CONSTRAINT demand_milestone_project_fk
        FOREIGN KEY (milestone_id, project_id)
        REFERENCES milestone(id, project_id)
        ON DELETE RESTRICT,
    CONSTRAINT demand_reference_trimmed CHECK (reference = btrim(reference))
);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES app_actor(id) ON DELETE RESTRICT,
    action varchar(100) NOT NULL,
    entity_type varchar(80) NOT NULL,
    entity_id uuid NOT NULL,
    reason varchar(500),
    request_id varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_event_entity_time_idx
    ON audit_event (entity_type, entity_id, created_at, id);

CREATE TABLE idempotency_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES app_actor(id) ON DELETE RESTRICT,
    operation varchar(100) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_hash char(64) NOT NULL,
    response_status integer CHECK (response_status BETWEEN 100 AND 599),
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_actor_operation_key_unique
        UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT idempotency_key_trimmed CHECK (idempotency_key = btrim(idempotency_key))
);

GRANT SELECT ON app_actor, client, project, milestone, demand TO milestone_app;
GRANT UPDATE ON milestone TO milestone_app;
GRANT INSERT ON demand TO milestone_app;
GRANT SELECT, INSERT ON audit_event TO milestone_app;
GRANT SELECT, INSERT, UPDATE ON idempotency_request TO milestone_app;
REVOKE UPDATE, DELETE ON audit_event FROM milestone_app;
