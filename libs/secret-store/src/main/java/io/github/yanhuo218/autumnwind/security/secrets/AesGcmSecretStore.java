package io.github.yanhuo218.autumnwind.security.secrets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 使用 AES-256-GCM 实现的本地信封加密 SecretStore。
 */
public final class AesGcmSecretStore implements SecretStore {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int AES_256_KEY_LENGTH = 32;
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec masterKey;
    private final String keyId;
    private final SecureRandom secureRandom;

    public AesGcmSecretStore(byte[] masterKey, String keyId) {
        this(masterKey, keyId, new SecureRandom());
    }

    AesGcmSecretStore(byte[] masterKey, String keyId, SecureRandom secureRandom) {
        Objects.requireNonNull(masterKey, "masterKey 不能为空");
        if (masterKey.length != AES_256_KEY_LENGTH) {
            throw new IllegalArgumentException("主密钥必须为 32 字节");
        }
        if (keyId == null || keyId.isBlank() || keyId.length() > 128) {
            throw new IllegalArgumentException("keyId 长度必须在 1 到 128 之间");
        }
        this.masterKey = new SecretKeySpec(Arrays.copyOf(masterKey, masterKey.length), "AES");
        this.keyId = keyId;
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom 不能为空");
    }

    /**
     * 从 Secret 挂载文件加载 Base64 编码的 32 字节主密钥。
     */
    public static AesGcmSecretStore fromBase64File(Path path, String keyId) {
        Objects.requireNonNull(path, "path 不能为空");
        byte[] decoded = null;
        try {
            String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
            decoded = Base64.getDecoder().decode(encoded);
            return new AesGcmSecretStore(decoded, keyId);
        } catch (IOException | IllegalArgumentException exception) {
            throw new SecretStoreException("无法加载主密钥。", exception);
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    @Override
    public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
        Objects.requireNonNull(plaintext, "plaintext 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        if (plaintext.length == 0) {
            throw new IllegalArgumentException("plaintext 不能为空");
        }

        byte[] dataKey = randomBytes(AES_256_KEY_LENGTH);
        byte[] payloadNonce = randomBytes(GCM_NONCE_LENGTH);
        byte[] wrappedDataKeyNonce = randomBytes(GCM_NONCE_LENGTH);

        try {
            byte[] ciphertext = crypt(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(dataKey, "AES"),
                    payloadNonce,
                    associatedData("payload", context),
                    plaintext);
            byte[] wrappedDataKey = crypt(
                    Cipher.ENCRYPT_MODE,
                    masterKey,
                    wrappedDataKeyNonce,
                    associatedData("data-key", context),
                    dataKey);

            return new EncryptedSecret(
                    EncryptedSecret.CURRENT_VERSION,
                    keyId,
                    wrappedDataKeyNonce,
                    wrappedDataKey,
                    payloadNonce,
                    ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new SecretStoreException("无法加密凭据。", exception);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
        Objects.requireNonNull(encryptedSecret, "encryptedSecret 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        if (!keyId.equals(encryptedSecret.keyId())) {
            throw new SecretStoreException("无法解密凭据。");
        }

        byte[] dataKey = null;
        try {
            dataKey = crypt(
                    Cipher.DECRYPT_MODE,
                    masterKey,
                    encryptedSecret.wrappedDataKeyNonce(),
                    associatedData("data-key", context),
                    encryptedSecret.wrappedDataKey());
            return crypt(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(dataKey, "AES"),
                    encryptedSecret.payloadNonce(),
                    associatedData("payload", context),
                    encryptedSecret.ciphertext());
        } catch (GeneralSecurityException exception) {
            throw new SecretStoreException("无法解密凭据。", exception);
        } finally {
            if (dataKey != null) {
                Arrays.fill(dataKey, (byte) 0);
            }
        }
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        secureRandom.nextBytes(value);
        return value;
    }

    private byte[] associatedData(String operation, SecretContext context) {
        byte[][] components = {
            operation.getBytes(StandardCharsets.UTF_8),
            Integer.toString(EncryptedSecret.CURRENT_VERSION).getBytes(StandardCharsets.UTF_8),
            keyId.getBytes(StandardCharsets.UTF_8),
            context.tenantId().getBytes(StandardCharsets.UTF_8),
            context.purpose().getBytes(StandardCharsets.UTF_8),
            context.ownerId().getBytes(StandardCharsets.UTF_8)
        };

        int size = Arrays.stream(components).mapToInt(component -> Integer.BYTES + component.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] component : components) {
            buffer.putInt(component.length);
            buffer.put(component);
        }
        return buffer.array();
    }

    private static byte[] crypt(
            int mode,
            SecretKeySpec key,
            byte[] nonce,
            byte[] associatedData,
            byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(input);
    }
}
