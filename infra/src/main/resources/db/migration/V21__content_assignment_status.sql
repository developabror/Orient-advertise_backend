-- Two-phase assignment lifecycle:
--   DRAFT     — created by POST /service/assignments; not yet active for resolution.
--               Auto-deleted after 1 hour if not confirmed.
--   CONFIRMED — committed by POST /service/assignments/{id}/confirm together with
--               any device exclusions, atomically in one transaction.
--   CANCELLED — operator-canceled before confirmation.
-- Existing rows are CONFIRMED so prior behaviour is preserved.
ALTER TABLE content_assignment ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE content_assignment ADD CONSTRAINT chk_assignment_status
    CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED'));

CREATE INDEX idx_assignment_status ON content_assignment (status);
