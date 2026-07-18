SET search_path TO model_registry;

CREATE TABLE endpoint_connection_test_jobs (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    endpoint_version BIGINT NOT NULL,
    credential_id UUID NOT NULL,
    requested_by_user_id UUID NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT endpoint_test_jobs_owner_endpoint_fk
        FOREIGN KEY (owner_user_id, endpoint_id)
        REFERENCES endpoints (owner_user_id, id) ON DELETE CASCADE,
    CONSTRAINT endpoint_test_jobs_endpoint_credential_fk
        FOREIGN KEY (endpoint_id, credential_id)
        REFERENCES endpoint_credentials (endpoint_id, id),
    CONSTRAINT endpoint_test_jobs_endpoint_version_check CHECK (endpoint_version >= 0),
    CONSTRAINT endpoint_test_jobs_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT endpoint_test_jobs_correlation_check
        CHECK (correlation_id ~ '^[A-Za-z0-9._-]{16,64}$'),
    CONSTRAINT endpoint_test_jobs_time_check CHECK (
        (status = 'QUEUED' AND started_at IS NULL AND completed_at IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT endpoint_test_jobs_failure_check CHECK (
        (status = 'FAILED' AND failure_code IS NOT NULL)
        OR (status <> 'FAILED' AND failure_code IS NULL)
    ),
    CONSTRAINT endpoint_test_jobs_version_check CHECK (version >= 0)
);

CREATE INDEX endpoint_test_jobs_status_created_idx
    ON endpoint_connection_test_jobs (status, created_at);
CREATE INDEX endpoint_test_jobs_owner_created_idx
    ON endpoint_connection_test_jobs (owner_user_id, created_at DESC);
