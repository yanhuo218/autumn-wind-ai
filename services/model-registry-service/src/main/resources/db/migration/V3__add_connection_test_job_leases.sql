SET search_path TO model_registry;

ALTER TABLE endpoint_connection_test_jobs
    ADD COLUMN lease_id UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE endpoint_connection_test_jobs
    DROP CONSTRAINT endpoint_test_jobs_time_check,
    DROP CONSTRAINT endpoint_test_jobs_failure_check;

UPDATE endpoint_connection_test_jobs
SET status = 'QUEUED',
    started_at = NULL,
    attempt_count = 0,
    version = version + 1
WHERE status = 'RUNNING';

UPDATE endpoint_connection_test_jobs
SET attempt_count = CASE WHEN started_at IS NULL THEN 0 ELSE 1 END
WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

UPDATE endpoint_connection_test_jobs
SET failure_code = 'INTERNAL_DEPENDENCY_ERROR'
WHERE status = 'FAILED'
  AND failure_code NOT IN (
      'CONFIGURATION_CHANGED',
      'TARGET_REJECTED',
      'PROVIDER_AUTHENTICATION_FAILED',
      'PROVIDER_RATE_LIMITED',
      'PROVIDER_UNAVAILABLE',
      'PROVIDER_RESPONSE_INVALID',
      'PROVIDER_ERROR',
      'CONNECTION_FAILED',
      'INTERNAL_DEPENDENCY_ERROR'
  );

ALTER TABLE endpoint_connection_test_jobs
    ADD CONSTRAINT endpoint_test_jobs_attempt_count_check CHECK (attempt_count >= 0),
    ADD CONSTRAINT endpoint_test_jobs_lease_time_check CHECK (
        (status = 'QUEUED'
            AND started_at IS NULL
            AND completed_at IS NULL
            AND lease_id IS NULL
            AND lease_expires_at IS NULL
            AND attempt_count = 0)
        OR (status = 'RUNNING'
            AND started_at IS NOT NULL
            AND completed_at IS NULL
            AND lease_id IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND lease_expires_at > started_at
            AND attempt_count >= 1)
        OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            AND completed_at IS NOT NULL
            AND lease_id IS NULL
            AND lease_expires_at IS NULL
            AND (
                (started_at IS NULL AND attempt_count = 0)
                OR (started_at IS NOT NULL
                    AND completed_at >= started_at
                    AND attempt_count >= 1)
            ))
    ),
    ADD CONSTRAINT endpoint_test_jobs_failure_check CHECK (
        (status = 'FAILED' AND failure_code IN (
            'CONFIGURATION_CHANGED',
            'TARGET_REJECTED',
            'PROVIDER_AUTHENTICATION_FAILED',
            'PROVIDER_RATE_LIMITED',
            'PROVIDER_UNAVAILABLE',
            'PROVIDER_RESPONSE_INVALID',
            'PROVIDER_ERROR',
            'CONNECTION_FAILED',
            'INTERNAL_DEPENDENCY_ERROR'
        ))
        OR (status <> 'FAILED' AND failure_code IS NULL)
    );

CREATE INDEX endpoint_test_jobs_claim_idx
    ON endpoint_connection_test_jobs (created_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');
