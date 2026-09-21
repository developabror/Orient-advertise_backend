-- Rollback of V45. TEST/DEV ONLY — this re-opens AUTH-01 (working admin/password logins).
-- Re-activates the five accounts V12 created active that are still on the V33 seed hash;
-- 'deactivated' was created inactive by V12 and stays that way.
UPDATE app_user
   SET is_active = TRUE,
       updated_at = CURRENT_TIMESTAMP
 WHERE password = '$2b$10$7G6HLwBeJv/5nByvd/R43.2TjtngYZFWjpO5ExQR5U5B4r9YUxUIS'
   AND username IN ('developabror@gmail.com', 'admin', 'operator', 'viewer', 'advertiser');
