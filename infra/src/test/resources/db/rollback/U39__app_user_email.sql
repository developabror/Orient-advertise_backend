-- Rollback of V39 (app_user.email). Drop the unique constraint first, then the column.
-- IF EXISTS keeps it idempotent.
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS ux_app_user_email;
ALTER TABLE app_user DROP COLUMN IF EXISTS email;
