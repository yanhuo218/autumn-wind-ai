CREATE SCHEMA IF NOT EXISTS model_registry;
SET search_path TO model_registry;

CREATE TABLE endpoints (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    request_timeout_seconds INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    current_credential_id UUID,
    last_test_status VARCHAR(16) NOT NULL DEFAULT 'NEVER',
    last_tested_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT endpoints_owner_id_unique UNIQUE (owner_user_id, id),
    CONSTRAINT endpoints_protocol_check CHECK (protocol IN ('OPENAI_COMPATIBLE')),
    CONSTRAINT endpoints_timeout_check CHECK (request_timeout_seconds BETWEEN 1 AND 120),
    CONSTRAINT endpoints_test_status_check CHECK (last_test_status IN ('NEVER', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT endpoints_test_time_check CHECK (
        (last_test_status = 'NEVER' AND last_tested_at IS NULL)
        OR (last_test_status IN ('SUCCEEDED', 'FAILED') AND last_tested_at IS NOT NULL)
    ),
    CONSTRAINT endpoints_version_check CHECK (version >= 0),
    CONSTRAINT endpoints_updated_at_check CHECK (updated_at >= created_at)
);

CREATE TABLE endpoint_credentials (
    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES endpoints (id) ON DELETE CASCADE,
    secret_version INTEGER NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    wrapped_data_key_nonce BYTEA NOT NULL,
    wrapped_data_key BYTEA NOT NULL,
    payload_nonce BYTEA NOT NULL,
    ciphertext BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    replaced_at TIMESTAMPTZ,
    CONSTRAINT endpoint_credentials_endpoint_id_unique UNIQUE (endpoint_id, id),
    CONSTRAINT endpoint_credentials_secret_version_check CHECK (secret_version >= 1),
    CONSTRAINT endpoint_credentials_wrapped_nonce_check CHECK (octet_length(wrapped_data_key_nonce) = 12),
    CONSTRAINT endpoint_credentials_wrapped_key_check CHECK (octet_length(wrapped_data_key) = 48),
    CONSTRAINT endpoint_credentials_payload_nonce_check CHECK (octet_length(payload_nonce) = 12),
    CONSTRAINT endpoint_credentials_ciphertext_check CHECK (octet_length(ciphertext) >= 16),
    CONSTRAINT endpoint_credentials_replaced_at_check CHECK (replaced_at IS NULL OR replaced_at >= created_at)
);

ALTER TABLE endpoints
    ADD CONSTRAINT endpoints_current_credential_fk
    FOREIGN KEY (id, current_credential_id) REFERENCES endpoint_credentials (endpoint_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX endpoints_owner_created_idx ON endpoints (owner_user_id, created_at DESC);
CREATE INDEX endpoint_credentials_endpoint_created_idx ON endpoint_credentials (endpoint_id, created_at DESC);

CREATE TABLE models (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    provider_model_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    interface_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    default_model BOOLEAN NOT NULL,
    capability_schema_version INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT models_owner_endpoint_fk FOREIGN KEY (owner_user_id, endpoint_id)
        REFERENCES endpoints (owner_user_id, id) ON DELETE CASCADE,
    CONSTRAINT models_owner_endpoint_provider_unique UNIQUE (owner_user_id, endpoint_id, provider_model_id),
    CONSTRAINT models_interface_type_check CHECK (interface_type IN ('CHAT_COMPLETIONS', 'IMAGE_GENERATION')),
    CONSTRAINT models_capability_schema_version_check CHECK (capability_schema_version >= 1),
    CONSTRAINT models_version_check CHECK (version >= 0),
    CONSTRAINT models_updated_at_check CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX models_one_default_per_interface_idx
    ON models (owner_user_id, interface_type)
    WHERE default_model;

CREATE INDEX models_owner_endpoint_idx ON models (owner_user_id, endpoint_id, created_at DESC);

CREATE TABLE model_capabilities (
    model_id UUID PRIMARY KEY REFERENCES models (id) ON DELETE CASCADE,
    text_input BOOLEAN NOT NULL,
    image_input BOOLEAN NOT NULL,
    file_input BOOLEAN NOT NULL,
    video_input BOOLEAN NOT NULL,
    output_modality VARCHAR(16) NOT NULL,
    streaming BOOLEAN NOT NULL,
    system_prompt BOOLEAN NOT NULL,
    reasoning BOOLEAN NOT NULL,
    context_length INTEGER NOT NULL,
    max_output_length INTEGER NOT NULL,
    CONSTRAINT model_capabilities_input_check CHECK (text_input OR image_input OR file_input OR video_input),
    CONSTRAINT model_capabilities_output_check CHECK (output_modality IN ('TEXT', 'IMAGE')),
    CONSTRAINT model_capabilities_lengths_check CHECK (
        context_length >= 1 AND max_output_length >= 1 AND max_output_length <= context_length
    )
);
