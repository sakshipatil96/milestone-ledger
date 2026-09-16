-- V1 granted broad default table privileges during initial bootstrap.  Runtime
-- access is now explicit: it may inspect setup data but cannot change it.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM milestone_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    REVOKE USAGE, SELECT ON SEQUENCES FROM milestone_app;

REVOKE INSERT, UPDATE, DELETE ON client, project FROM milestone_app;
REVOKE INSERT, UPDATE, DELETE ON app_actor FROM milestone_app;
REVOKE CREATE ON SCHEMA public FROM milestone_app;
