INSERT INTO app_actor (id, external_subject, display_name, role)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'certifier', 'Demo Project Engineer', 'CERTIFIER'),
    ('10000000-0000-0000-0000-000000000002', 'accounts', 'Demo Accounts Executive', 'ACCOUNTS'),
    ('10000000-0000-0000-0000-000000000003', 'manager', 'Demo Finance Manager', 'MANAGER'),
    ('10000000-0000-0000-0000-000000000004', 'system', 'Milestone Ledger', 'SYSTEM');

INSERT INTO client (id, name)
VALUES ('20000000-0000-0000-0000-000000000001', 'Northstar Public Works Authority');

INSERT INTO project (id, client_id, code, name, currency)
VALUES (
    '30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    'DEMO-RIVER-001',
    'River Link Upgrade — Synthetic Demo',
    'INR'
);

INSERT INTO milestone (id, project_id, sequence_number, name)
VALUES
    (
        '40000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001',
        1,
        'Foundation package complete'
    ),
    (
        '40000000-0000-0000-0000-000000000002',
        '30000000-0000-0000-0000-000000000001',
        2,
        'Structural package complete'
    );
