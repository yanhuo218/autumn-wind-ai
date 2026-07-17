SET search_path TO notification;

ALTER TABLE email_jobs
    ADD COLUMN smtp_config_version BIGINT;

ALTER TABLE email_jobs
    ADD CONSTRAINT email_jobs_smtp_config_version_check CHECK (
        smtp_config_version IS NULL OR smtp_config_version >= 0
    );

ALTER TABLE smtp_config
    ADD COLUMN last_test_job_id UUID REFERENCES email_jobs (id),
    ADD COLUMN last_test_config_version BIGINT;

ALTER TABLE smtp_config
    ADD CONSTRAINT smtp_config_test_job_check CHECK (
        (last_test_job_id IS NULL AND last_test_config_version IS NULL)
        OR (last_test_job_id IS NOT NULL AND last_test_config_version >= 0)
    );
