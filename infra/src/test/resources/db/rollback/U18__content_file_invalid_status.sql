ALTER TABLE content_file DROP COLUMN IF EXISTS invalid_reason;
ALTER TABLE content_file DROP CONSTRAINT IF EXISTS chk_content_file_status;
ALTER TABLE content_file ADD CONSTRAINT chk_content_file_status
    CHECK (status IN ('UPLOADED', 'TRANSCODING', 'READY', 'FAILED'));
