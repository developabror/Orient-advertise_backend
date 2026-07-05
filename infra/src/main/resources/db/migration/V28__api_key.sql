-- API keys for external integrations (separate from JWT/user auth). Keys are stored as
-- SHA-256 hashes; the plaintext is shown to the customer once at creation and never
-- recoverable. The 8-char prefix is kept in cleartext so the key can be identified in
-- logs and the admin UI without leaking the secret. Lookup is by (key_hash, status)
-- so a revoked key returns nothing — auth fails immediately on the next request.
CREATE TABLE api_key (
    id BIGSERIAL PRIMARY KEY,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    key_prefix VARCHAR(16) NOT NULL,
    client_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    revoked_by VARCHAR(100),
    CONSTRAINT chk_api_key_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_api_key_hash_status ON api_key (key_hash, status);
