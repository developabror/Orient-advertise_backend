-- V32: Add thumbnail_storage_key to content_file.
--
-- Why: the transcode pipeline now generates a poster JPEG (first frame past the
-- one-second mark) and uploads it to the content-thumbnails bucket. The key is
-- stored alongside processed_storage_key so the listing/detail responses can
-- resolve it to a presigned URL without an extra round trip.
--
-- Nullable: thumbnail generation is best-effort — a failure of the ffmpeg poster
-- step must NOT flip the file out of READY (the processed MP4 is the load-bearing
-- artifact). Rows where the poster step failed simply leave this column null,
-- and the API returns a null thumbnailUrl for those rows. Backfilling existing
-- rows is intentionally skipped: re-transcoding only on poster regeneration is
-- wasteful, and the FE handles null thumbnails gracefully.

ALTER TABLE content_file
    ADD COLUMN thumbnail_storage_key VARCHAR(500);
