package io.github.yanhuo218.autumnwind.security.secrets;

import java.util.Arrays;
import java.util.Objects;

/**
 * 保存信封加密结果，不包含明文数据密钥。
 */
public final class EncryptedSecret {

    public static final int CURRENT_VERSION = 1;
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int MINIMUM_CIPHERTEXT_LENGTH = 16;
    private static final int WRAPPED_AES_256_KEY_LENGTH = 48;

    private final int version;
    private final String keyId;
    private final byte[] wrappedDataKeyNonce;
    private final byte[] wrappedDataKey;
    private final byte[] payloadNonce;
    private final byte[] ciphertext;

    public EncryptedSecret(
            int version,
            String keyId,
            byte[] wrappedDataKeyNonce,
            byte[] wrappedDataKey,
            byte[] payloadNonce,
            byte[] ciphertext) {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("不支持的密文版本");
        }
        this.version = version;
        this.keyId = requireText(keyId, "keyId");
        this.wrappedDataKeyNonce = requireLength(wrappedDataKeyNonce, GCM_NONCE_LENGTH, "wrappedDataKeyNonce");
        this.wrappedDataKey = requireLength(wrappedDataKey, WRAPPED_AES_256_KEY_LENGTH, "wrappedDataKey");
        this.payloadNonce = requireLength(payloadNonce, GCM_NONCE_LENGTH, "payloadNonce");
        this.ciphertext = requireMinimumLength(ciphertext, MINIMUM_CIPHERTEXT_LENGTH, "ciphertext");
    }

    public int version() {
        return version;
    }

    public String keyId() {
        return keyId;
    }

    public byte[] wrappedDataKeyNonce() {
        return Arrays.copyOf(wrappedDataKeyNonce, wrappedDataKeyNonce.length);
    }

    public byte[] wrappedDataKey() {
        return Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
    }

    public byte[] payloadNonce() {
        return Arrays.copyOf(payloadNonce, payloadNonce.length);
    }

    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " 长度必须在 1 到 128 之间");
        }
        return value;
    }

    private static byte[] requireLength(byte[] value, int expectedLength, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.length != expectedLength) {
            throw new IllegalArgumentException(name + " 长度必须为 " + expectedLength);
        }
        return Arrays.copyOf(value, value.length);
    }

    private static byte[] requireMinimumLength(byte[] value, int minimumLength, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.length < minimumLength) {
            throw new IllegalArgumentException(name + " 长度不能小于 " + minimumLength);
        }
        return Arrays.copyOf(value, value.length);
    }
}
