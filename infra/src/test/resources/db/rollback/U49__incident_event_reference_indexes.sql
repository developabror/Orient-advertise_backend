-- Rollback of V49 (incident → event reference indexes). IF EXISTS keeps it idempotent.
DROP INDEX IF EXISTS idx_incident_first_event;
DROP INDEX IF EXISTS idx_incident_last_event;
