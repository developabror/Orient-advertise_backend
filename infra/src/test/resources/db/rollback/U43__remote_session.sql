-- Rollback of V43 (remote view/control sessions). DROP TABLE removes the table's indexes,
-- UNIQUE constraint and CHECK constraints atomically, so no ordering is needed there (same
-- reasoning as U40). The six device columns are dropped separately; IF EXISTS keeps the whole
-- script idempotent.
DROP TABLE IF EXISTS remote_session;

ALTER TABLE device DROP COLUMN IF EXISTS remote_supported;
ALTER TABLE device DROP COLUMN IF EXISTS remote_input;
ALTER TABLE device DROP COLUMN IF EXISTS remote_transport;
ALTER TABLE device DROP COLUMN IF EXISTS remote_max_width;
ALTER TABLE device DROP COLUMN IF EXISTS remote_max_height;
ALTER TABLE device DROP COLUMN IF EXISTS remote_caps_at;
