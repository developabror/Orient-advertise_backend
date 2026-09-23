-- V53 (VG-06): key the playback anchor on the CONTENT version, not the version NUMBER.
--
-- V40 keyed one immutable anchor per (assignment_id, version_number). ContentAssignment.bumpVersion()
-- has no callers, so version_number is always 1: after any playlist edit the lookup kept returning the
-- ORIGINAL row, whose activate_at was long past. The coordinated cut-over therefore only ever worked
-- for a brand-new assignment — every edit made each screen flip the moment it finished downloading, so
-- screens on the same assignment (every multi-screen site, every sync group) showed different content
-- until the slowest download finished.
--
-- content_version is a pure hash of the assignment's items + dwells (ContentVersionService), so an
-- edit produces a new key and therefore a NEW anchor with a future activate_at, and every member on
-- that assignment agrees on it. Existing data holds at most one row per assignment, so it is already
-- unique under the new key and no data has to be rewritten.
--
-- version_number is KEPT: it is still written, still returned, and the readiness monitor logs it.
-- Named constraints on both sides so the same DDL runs on H2 (MODE=PostgreSQL, tests) and Postgres 17.

ALTER TABLE playback_sync_schedule DROP CONSTRAINT uq_playback_sched;

ALTER TABLE playback_sync_schedule
    ADD CONSTRAINT uq_playback_sched_cv UNIQUE (assignment_id, content_version);
