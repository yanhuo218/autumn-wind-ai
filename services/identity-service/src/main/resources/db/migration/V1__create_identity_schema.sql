CREATE SCHEMA IF NOT EXISTS identity;
SET search_path TO identity;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL,
    email_verified_at TIMESTAMPTZ,
    failed_login_count INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    deletion_requested_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT users_status_check CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED', 'DELETION_PENDING', 'DELETED')
    ),
    CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT users_failed_login_count_check CHECK (failed_login_count >= 0)
);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    csrf_token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_sessions_token_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT auth_sessions_csrf_token_hash_check CHECK (csrf_token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX auth_sessions_user_active_idx
    ON auth_sessions (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE email_verifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT email_verifications_token_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX email_verifications_user_idx ON email_verifications (user_id, created_at DESC);

CREATE TABLE password_resets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT password_resets_token_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX password_resets_user_idx ON password_resets (user_id, created_at DESC);

CREATE TABLE auth_policies (
    id SMALLINT PRIMARY KEY,
    public_registration_enabled BOOLEAN NOT NULL,
    email_verification_required BOOLEAN NOT NULL,
    email_domain_policy_mode VARCHAR(16) NOT NULL,
    password_min_length INTEGER NOT NULL,
    password_max_length INTEGER NOT NULL,
    login_failure_limit INTEGER NOT NULL,
    login_lock_duration_seconds INTEGER NOT NULL,
    verification_ttl_seconds INTEGER NOT NULL,
    verification_resend_cooldown_seconds INTEGER NOT NULL,
    verification_failure_limit INTEGER NOT NULL,
    password_reset_ttl_seconds INTEGER NOT NULL,
    terms_acceptance_required BOOLEAN NOT NULL,
    privacy_acceptance_required BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_policies_singleton_check CHECK (id = 1),
    CONSTRAINT auth_policies_domain_mode_check CHECK (email_domain_policy_mode IN ('ALLOWLIST', 'BLOCKLIST')),
    CONSTRAINT auth_policies_password_length_check CHECK (
        password_min_length >= 12 AND password_max_length >= password_min_length AND password_max_length <= 1024
    ),
    CONSTRAINT auth_policies_login_failure_limit_check CHECK (login_failure_limit BETWEEN 1 AND 100),
    CONSTRAINT auth_policies_lock_duration_check CHECK (login_lock_duration_seconds BETWEEN 60 AND 86400),
    CONSTRAINT auth_policies_verification_ttl_check CHECK (verification_ttl_seconds BETWEEN 300 AND 604800),
    CONSTRAINT auth_policies_resend_cooldown_check CHECK (verification_resend_cooldown_seconds BETWEEN 1 AND 86400),
    CONSTRAINT auth_policies_verification_failure_limit_check CHECK (verification_failure_limit BETWEEN 1 AND 100),
    CONSTRAINT auth_policies_reset_ttl_check CHECK (password_reset_ttl_seconds BETWEEN 300 AND 86400)
);

CREATE TABLE auth_policy_email_domains (
    policy_id SMALLINT NOT NULL REFERENCES auth_policies (id) ON DELETE CASCADE,
    domain VARCHAR(253) NOT NULL,
    PRIMARY KEY (policy_id, domain)
);

INSERT INTO auth_policies (
    id,
    public_registration_enabled,
    email_verification_required,
    email_domain_policy_mode,
    password_min_length,
    password_max_length,
    login_failure_limit,
    login_lock_duration_seconds,
    verification_ttl_seconds,
    verification_resend_cooldown_seconds,
    verification_failure_limit,
    password_reset_ttl_seconds,
    terms_acceptance_required,
    privacy_acceptance_required,
    updated_at
) VALUES (1, FALSE, FALSE, 'BLOCKLIST', 12, 128, 5, 900, 86400, 60, 5, 3600, FALSE, FALSE, CURRENT_TIMESTAMP);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT outbox_events_version_check CHECK (event_version >= 1),
    CONSTRAINT outbox_events_attempts_check CHECK (publish_attempts >= 0)
);

CREATE INDEX outbox_events_unpublished_idx
    ON outbox_events (occurred_at)
    WHERE published_at IS NULL;
