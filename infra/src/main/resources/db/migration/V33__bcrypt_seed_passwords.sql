-- AUTH-1: passwords are now BCrypt-hashed and verified with PasswordEncoder.matches().
-- The V12 seeds stored the literal plaintext 'password'; convert any row still holding
-- that exact value to its BCrypt hash so the documented dev logins keep working.
--
-- The hash below is BCrypt($2b$, cost 10) of 'password' (verified to match Spring's
-- BCryptPasswordEncoder — see AuthServiceTest.bcryptSeedHash_matchesPassword).
--
-- PRODUCTION NOTE: any real account whose password is not the literal 'password' is left
-- untouched and will fail login until reset — production MUST issue a password reset for
-- all accounts (those rows were previously plaintext and are not safe).
UPDATE app_user
   SET password = '$2b$10$7G6HLwBeJv/5nByvd/R43.2TjtngYZFWjpO5ExQR5U5B4r9YUxUIS'
 WHERE password = 'password';
