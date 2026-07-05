DROP INDEX IF EXISTS idx_assignment_status;
ALTER TABLE content_assignment DROP CONSTRAINT IF EXISTS chk_assignment_status;
ALTER TABLE content_assignment DROP COLUMN IF EXISTS status;
