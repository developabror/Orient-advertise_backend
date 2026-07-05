-- V31: Make content_file.project_id nullable.
--
-- Why: the upload flow no longer rejects requests with a missing/unknown projectId.
-- Operators are allowed to upload bytes first ("orphan" content) and bind the file to
-- a project later via PATCH /api/content/{id}/project. This unblocks the FE during
-- early integration when the project picker isn't wired up yet, and matches the
-- broader product principle of "don't fail the upload if context is incomplete —
-- let it be filled in later."
--
-- The FK to project(id) stays in place, so non-null values remain referentially valid.
-- Existing rows are unaffected — they all have project_id set today; new rows are
-- the only ones that may be null.

ALTER TABLE content_file
    ALTER COLUMN project_id DROP NOT NULL;
