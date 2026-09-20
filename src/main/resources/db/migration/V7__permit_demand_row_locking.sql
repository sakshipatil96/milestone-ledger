-- PostgreSQL requires UPDATE privilege for SELECT ... FOR UPDATE.  The trigger
-- lets the runtime acquire the lock without granting a way to alter posted demand facts.
CREATE OR REPLACE FUNCTION prevent_demand_fact_changes()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'demand facts are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER demand_facts_immutable BEFORE UPDATE ON demand
    FOR EACH ROW EXECUTE FUNCTION prevent_demand_fact_changes();

GRANT UPDATE (id) ON demand TO milestone_app;
