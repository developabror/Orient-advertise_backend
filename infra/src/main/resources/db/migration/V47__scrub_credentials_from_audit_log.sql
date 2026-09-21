-- AUTH-06: until v1.0.139 AuditFilter stored request/response bodies with an exact-name masker, so
-- these rows hold live credentials in plaintext: old/new passwords (change + reset), refresh
-- responses, freshly minted API keys (rawKey), device tokens returned by registration (before
-- v1.0.137), and rejected passwords echoed in validation 400s (rejectedValue). Clear them.
-- Irreversible by design (see U47). This scrubs the live table only, not existing database dumps.
UPDATE audit_log
   SET request_body = NULL,
       response_body = NULL
 WHERE http_method = 'POST'
   AND path IN ('/api/me/password', '/api/auth/reset-password', '/api/auth/refresh', '/api/admin/api-keys');

UPDATE audit_log
   SET response_body = NULL
 WHERE path = '/api/devices/register'
    OR (path IN ('/api/users', '/api/auth/login') AND response_status = 400);

-- Login / user-create request bodies were masked by a regex that stopped at an escaped quote (the
-- tail of such a password stayed in the row, with no trace of the escape left) and never matched a
-- numeric password. A half-masked body cannot be told from a clean one without parsing, so clear
-- them all. Rows written from v1.0.139 on are masked by parsing and keep their username trail.
UPDATE audit_log
   SET request_body = NULL
 WHERE http_method = 'POST'
   AND path IN ('/api/auth/login', '/api/users');
