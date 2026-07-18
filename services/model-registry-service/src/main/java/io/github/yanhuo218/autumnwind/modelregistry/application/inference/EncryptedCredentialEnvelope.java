package io.github.yanhuo218.autumnwind.modelregistry.application.inference;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;

import java.util.Base64;
import java.util.Objects;

public record EncryptedCredentialEnvelope(
        int version,
        String keyId,
        String wrappedDataKeyNonce,
        String wrappedDataKey,
        String payloadNonce,
        String ciphertext
) {

    public EncryptedCredentialEnvelope {
        Objects.requireNonNull(keyId, "密钥标识不能为空。");
        Objects.requireNonNull(wrappedDataKeyNonce, "数据密钥随机数不能为空。");
        Objects.requireNonNull(wrappedDataKey, "数据密钥密文不能为空。");
        Objects.requireNonNull(payloadNonce, "载荷随机数不能为空。");
        Objects.requireNonNull(ciphertext, "载荷密文不能为空。");
    }

    public static EncryptedCredentialEnvelope from(EncryptedSecret secret) {
        Objects.requireNonNull(secret, "加密凭据不能为空。");
        Base64.Encoder encoder = Base64.getEncoder();
        return new EncryptedCredentialEnvelope(
                secret.version(),
                secret.keyId(),
                encoder.encodeToString(secret.wrappedDataKeyNonce()),
                encoder.encodeToString(secret.wrappedDataKey()),
                encoder.encodeToString(secret.payloadNonce()),
                encoder.encodeToString(secret.ciphertext())
        );
    }

    public EncryptedSecret toEncryptedSecret() {
        Base64.Decoder decoder = Base64.getDecoder();
        return new EncryptedSecret(
                version,
                keyId,
                decoder.decode(wrappedDataKeyNonce),
                decoder.decode(wrappedDataKey),
                decoder.decode(payloadNonce),
                decoder.decode(ciphertext)
        );
    }

    @Override
    public String toString() {
        return "EncryptedCredentialEnvelope[version=" + version + ", keyId=<REDACTED>]";
    }
}
