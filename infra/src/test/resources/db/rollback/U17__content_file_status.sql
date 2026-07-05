DROP INDEX IF EXISTS idx_content_file_status;
ALTER TABLE content_file DROP CONSTRAINT IF EXISTS chk_content_file_status;
ALTER TABLE content_file DROP COLUMN IF EXISTS status;
