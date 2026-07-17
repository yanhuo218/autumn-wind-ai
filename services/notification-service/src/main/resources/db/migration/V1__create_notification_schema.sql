CREATE SCHEMA IF NOT EXISTS notification;
SET search_path TO notification;

CREATE TABLE smtp_credentials (
    id UUID PRIMARY KEY,
    secret_version INTEGER NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    wrapped_data_key_nonce BYTEA NOT NULL,
    wrapped_data_key BYTEA NOT NULL,
    payload_nonce BYTEA NOT NULL,
    ciphertext BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    replaced_at TIMESTAMPTZ,
    CONSTRAINT smtp_credentials_secret_version_check CHECK (secret_version >= 1),
    CONSTRAINT smtp_credentials_wrapped_nonce_check CHECK (octet_length(wrapped_data_key_nonce) = 12),
    CONSTRAINT smtp_credentials_wrapped_key_check CHECK (octet_length(wrapped_data_key) = 48),
    CONSTRAINT smtp_credentials_payload_nonce_check CHECK (octet_length(payload_nonce) = 12),
    CONSTRAINT smtp_credentials_ciphertext_check CHECK (octet_length(ciphertext) >= 16),
    CONSTRAINT smtp_credentials_replaced_at_check CHECK (replaced_at IS NULL OR replaced_at >= created_at)
);

CREATE TABLE smtp_config (
    id SMALLINT PRIMARY KEY,
    host VARCHAR(253) NOT NULL,
    port INTEGER NOT NULL,
    security_mode VARCHAR(16) NOT NULL,
    username VARCHAR(320),
    current_credential_id UUID REFERENCES smtp_credentials (id),
    from_address VARCHAR(320) NOT NULL,
    from_name VARCHAR(200) NOT NULL,
    last_test_status VARCHAR(16) NOT NULL,
    last_tested_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT smtp_config_singleton_check CHECK (id = 1),
    CONSTRAINT smtp_config_port_check CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT smtp_config_security_mode_check CHECK (security_mode IN ('STARTTLS', 'TLS')),
    CONSTRAINT smtp_config_test_status_check CHECK (last_test_status IN ('NEVER', 'QUEUED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT smtp_config_version_check CHECK (version >= 0)
);

CREATE TABLE email_templates (
    template_key VARCHAR(64) NOT NULL,
    locale VARCHAR(16) NOT NULL,
    subject_template VARCHAR(300) NOT NULL,
    text_template TEXT NOT NULL,
    html_template TEXT,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    PRIMARY KEY (template_key, locale),
    CONSTRAINT email_templates_key_check CHECK (template_key ~ '^[a-z][a-z0-9.-]{2,63}$'),
    CONSTRAINT email_templates_locale_check CHECK (locale ~ '^[a-z]{2,3}(-[A-Z]{2})?$'),
    CONSTRAINT email_templates_version_check CHECK (version >= 0)
);

CREATE TABLE email_jobs (
    id UUID PRIMARY KEY,
    delivery_request_id UUID NOT NULL UNIQUE,
    user_id UUID,
    recipient_email VARCHAR(320) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    template_key VARCHAR(64),
    content_reference_id UUID,
    locale VARCHAR(16),
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    correlation_id VARCHAR(64) NOT NULL,
    CONSTRAINT email_jobs_purpose_check CHECK (
        purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'SECURITY_NOTIFICATION', 'SMTP_TEST')
    ),
    CONSTRAINT email_jobs_status_check CHECK (
        status IN ('QUEUED', 'SENDING', 'SUCCEEDED', 'RETRY_SCHEDULED', 'FAILED')
    ),
    CONSTRAINT email_jobs_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT email_jobs_content_check CHECK (
        (purpose = 'SMTP_TEST' AND template_key IS NULL AND content_reference_id IS NULL)
        OR (purpose <> 'SMTP_TEST' AND template_key IS NOT NULL AND content_reference_id IS NOT NULL)
    ),
    CONSTRAINT email_jobs_state_time_check CHECK (
        (status = 'QUEUED' AND next_attempt_at IS NULL AND lease_expires_at IS NULL AND completed_at IS NULL)
        OR (status = 'SENDING' AND next_attempt_at IS NULL AND lease_expires_at IS NOT NULL AND completed_at IS NULL)
        OR (status = 'RETRY_SCHEDULED' AND next_attempt_at IS NOT NULL AND lease_expires_at IS NULL AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND next_attempt_at IS NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT email_jobs_completion_time_check CHECK (completed_at IS NULL OR completed_at >= created_at)
);

CREATE INDEX email_jobs_dispatch_idx
    ON email_jobs ((COALESCE(next_attempt_at, created_at)), created_at)
    WHERE status IN ('QUEUED', 'RETRY_SCHEDULED');

CREATE INDEX email_jobs_expired_lease_idx
    ON email_jobs (lease_expires_at)
    WHERE status = 'SENDING';

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES email_jobs (id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(100),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (job_id, attempt_number),
    CONSTRAINT delivery_attempts_number_check CHECK (attempt_number >= 1),
    CONSTRAINT delivery_attempts_status_check CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT delivery_attempts_state_time_check CHECK (
        (status = 'STARTED' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT delivery_attempts_completion_time_check CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE consumed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX consumed_events_consumed_at_idx ON consumed_events (consumed_at);
