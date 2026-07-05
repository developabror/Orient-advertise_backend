ALTER TABLE remote_action DROP CONSTRAINT chk_remote_action_status;
ALTER TABLE remote_action ADD CONSTRAINT chk_remote_action_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'FAILED'));
