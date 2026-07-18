ALTER TABLE identity.auth_sessions
    ALTER COLUMN token_hash TYPE VARCHAR(64) USING btrim(token_hash)::VARCHAR(64);

ALTER TABLE identity.email_verifications
    ALTER COLUMN token_hash TYPE VARCHAR(64) USING btrim(token_hash)::VARCHAR(64);

ALTER TABLE identity.password_resets
    ALTER COLUMN token_hash TYPE VARCHAR(64) USING btrim(token_hash)::VARCHAR(64);
