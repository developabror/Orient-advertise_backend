-- Rollback of V51 (Postgres only; V51 is a no-op on H2): back to a row-by-row checked constraint.
ALTER TABLE playlist_item
    DROP CONSTRAINT IF EXISTS uq_playlist_position,
    ADD CONSTRAINT uq_playlist_position UNIQUE (playlist_id, position);
