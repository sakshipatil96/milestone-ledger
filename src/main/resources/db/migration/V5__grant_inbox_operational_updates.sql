-- A row-level lock requires UPDATE privilege.  Limit the runtime role to
-- operational inbox state so normalized accepted receipt facts remain fixed.
GRANT UPDATE (status, attempt_count, next_attempt_at, last_error_code, processed_at)
    ON inbox_event TO milestone_app;
