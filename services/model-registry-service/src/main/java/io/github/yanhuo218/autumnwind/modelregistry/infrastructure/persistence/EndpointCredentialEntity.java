package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "endpoint_credentials", schema = "model_registry")
public class EndpointCredentialEntity {

    @Id
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "secret_version", nullable = false)
    private int secretVersion;

    @Column(name = "key_id", nullable = false, length = 128)
    private String keyId;

    @Column(name = "wrapped_data_key_nonce", nullable = false)
    private byte[] wrappedDataKeyNonce;

    @Column(name = "wrapped_data_key", nullable = false)
    private byte[] wrappedDataKey;

    @Column(name = "payload_nonce", nullable = false)
    private byte[] payloadNonce;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    protected EndpointCredentialEntity() {
    }

    public static EndpointCredentialEntity create(
            UUID id,
            UUID endpointId,
            EncryptedSecret secret,
            Instant now
    ) {
        Objects.requireNonNull(secret, "端点加密凭据不能为空。");
        EndpointCredentialEntity entity = new EndpointCredentialEntity();
        entity.id = Objects.requireNonNull(id, "端点凭据标识不能为空。");
        entity.endpointId = Objects.requireNonNull(endpointId, "端点标识不能为空。");
        entity.secretVersion = secret.version();
        entity.keyId = secret.keyId();
        entity.wrappedDataKeyNonce = secret.wrappedDataKeyNonce();
        entity.wrappedDataKey = secret.wrappedDataKey();
        entity.payloadNonce = secret.payloadNonce();
        entity.ciphertext = secret.ciphertext();
        entity.createdAt = Objects.requireNonNull(now, "端点凭据创建时间不能为空。");
        return entity;
    }

    public EncryptedSecret toEncryptedSecret() {
        return new EncryptedSecret(
                secretVersion,
                keyId,
                wrappedDataKeyNonce,
                wrappedDataKey,
                payloadNonce,
                ciphertext
        );
    }

    public void markReplaced(Instant now) {
        if (replacedAt == null) {
            replacedAt = Objects.requireNonNull(now, "端点凭据替换时间不能为空。");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID endpointId() {
        return endpointId;
    }

    public Instant replacedAt() {
        return replacedAt;
    }
}
