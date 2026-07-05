-- Track the processed (transcoded) output's MinIO key separately from the raw key.
-- raw key (storage_key)        → content-raw bucket
-- processed key (this column)  → content-processed bucket, written by ffmpeg
ALTER TABLE content_file ADD COLUMN processed_storage_key VARCHAR(500);
