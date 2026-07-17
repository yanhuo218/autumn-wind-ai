package io.github.yanhuo218.autumnwind.notification.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "smtp_credentials", schema = "notification")
public class SmtpCredentialEntity {

    @Id
    private UUID id;

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

    protected SmtpCredentialEntity() {
    }

    public static SmtpCredentialEntity create(UUID id, EncryptedSecret secret, Instant now) {
        Objects.requireNonNull(secret, "SMTP 加密凭据不能为空。");
        SmtpCredentialEntity entity = new SmtpCredentialEntity();
        entity.id = Objects.requireNonNull(id, "SMTP 凭据标识不能为空。");
        entity.secretVersion = secret.version();
        entity.keyId = secret.keyId();
        entity.wrappedDataKeyNonce = secret.wrappedDataKeyNonce();
        entity.wrappedDataKey = secret.wrappedDataKey();
        entity.payloadNonce = secret.payloadNonce();
        entity.ciphertext = secret.ciphertext();
        entity.createdAt = Objects.requireNonNull(now, "SMTP 凭据创建时间不能为空。");
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
            replacedAt = Objects.requireNonNull(now, "SMTP 凭据替换时间不能为空。");
        }
    }

    public UUID id() {
        return id;
    }

    public Instant replacedAt() {
        return replacedAt;
    }

}
