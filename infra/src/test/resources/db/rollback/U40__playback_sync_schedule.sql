-- Rollback of V40 (server-anchored playback schedule). The UNIQUE constraint is backed by an
-- implicit index that DROP TABLE removes atomically, so no constraint-before-index ordering is
-- needed here (unlike the FK-backed indexes in U37). IF EXISTS keeps it idempotent.
DROP TABLE IF EXISTS playback_sync_schedule;
