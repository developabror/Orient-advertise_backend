-- Rollback of V46 (device re-registration window). IF EXISTS keeps it idempotent.
ALTER TABLE device DROP COLUMN IF EXISTS reregistration_allowed_until;
