-- Rollback of V47: nothing to undo. V47 deleted credential material from audit_log bodies; that data
-- is gone on purpose and must not be restored. Kept as a file so the U<N> sequence has no gap.
SELECT 1;
