DROP INDEX IF EXISTS idx_device_content_version;
ALTER TABLE content_assignment DROP COLUMN IF EXISTS version_number;
ALTER TABLE device DROP COLUMN IF EXISTS current_content_version;
