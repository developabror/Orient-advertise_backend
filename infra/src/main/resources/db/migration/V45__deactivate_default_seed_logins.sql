-- AUTH-01: V12 seeds dev users with the password 'password' and V33 rewrites them to a fixed,
-- publicly known BCrypt hash. Both run on EVERY database, so every environment — production
-- included — started with a working admin/password login.
--
-- Deactivate every account still on that exact hash. Accounts whose owner changed the password
-- carry a different hash and are left untouched. Dev environments get the five accounts V12
-- created active back from DefaultLoginPolicy when app.seed.enabled=true; everywhere else the
-- first admin comes from APP_BOOTSTRAP_ADMIN_USERNAME / APP_BOOTSTRAP_ADMIN_PASSWORD
-- (BootstrapAdminProvisioner).
UPDATE app_user
   SET is_active = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE is_active = TRUE
   AND password = '$2b$10$7G6HLwBeJv/5nByvd/R43.2TjtngYZFWjpO5ExQR5U5B4r9YUxUIS';
