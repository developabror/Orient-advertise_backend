-- Add CONFIRMED_LATE to the remote_action.status check constraint.
-- A device that comes back online after the action's deadline can still confirm; the
-- result is recorded so the operator console knows the command eventually executed,
-- distinguished from on-time confirmations for SLO/metrics purposes.
ALTER TABLE remote_action DROP CONSTRAINT chk_remote_action_status;

ALTER TABLE remote_action ADD CONSTRAINT chk_remote_action_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'CONFIRMED_LATE', 'EXPIRED', 'FAILED'));
