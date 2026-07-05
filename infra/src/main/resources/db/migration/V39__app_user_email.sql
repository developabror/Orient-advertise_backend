-- Recovery email for self-service password reset (forgot-password is keyed by email).
-- Nullable: existing users have NULL until they set one (PUT /api/me/email) or an admin
-- recreates them with one. NULLs do not collide under a UNIQUE constraint, so no backfill
-- is needed. The value is app-normalized (lowercased+trimmed in AppUser.setEmail) so a plain
-- UNIQUE constraint is portable to H2 PG-mode without a functional LOWER() index.
ALTER TABLE app_user ADD COLUMN email VARCHAR(255);
ALTER TABLE app_user ADD CONSTRAINT ux_app_user_email UNIQUE (email);
