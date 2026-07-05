DROP INDEX IF EXISTS idx_content_file_duration;
ALTER TABLE content_file DROP COLUMN IF EXISTS duration_seconds;
