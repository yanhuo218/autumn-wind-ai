package io.github.yanhuo218.autumnwind.security.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmSecretStoreTest {

    private static final SecretContext CONTEXT = new SecretContext("tenant-1", "model-endpoint", "endpoint-1");

    @Test
    void encryptsAndDecryptsWithoutMutatingInput() {
        byte[] masterKey = randomBytes(32);
        byte[] plaintext = "credential-value".getBytes(StandardCharsets.UTF_8);
        byte[] original = Arrays.copyOf(plaintext, plaintext.length);
        SecretStore store = new AesGcmSecretStore(masterKey, "local-v1");

        EncryptedSecret encrypted = store.encrypt(plaintext, CONTEXT);
        byte[] decrypted = store.decrypt(encrypted, CONTEXT);

        assertArrayEquals(original, plaintext);
        assertArrayEquals(original, decrypted);
        Arrays.fill(decrypted, (byte) 0);
        Arrays.fill(masterKey, (byte) 0);
        Arrays.fill(plaintext, (byte) 0);
        Arrays.fill(original, (byte) 0);
    }

    @Test
    void producesDifferentCiphertextForSamePlaintext() {
        byte[] masterKey = randomBytes(32);
        byte[] plaintext = "credential-value".getBytes(StandardCharsets.UTF_8);
        SecretStore store = new AesGcmSecretStore(masterKey, "local-v1");

        EncryptedSecret first = store.encrypt(plaintext, CONTEXT);
        EncryptedSecret second = store.encrypt(plaintext, CONTEXT);

        assertFalse(Arrays.equals(first.ciphertext(), second.ciphertext()));
        assertFalse(Arrays.equals(first.wrappedDataKey(), second.wrappedDataKey()));
        Arrays.fill(masterKey, (byte) 0);
        Arrays.fill(plaintext, (byte) 0);
    }

    @Test
    void rejectsDifferentContext() {
        byte[] masterKey = randomBytes(32);
        byte[] plaintext = "credential-value".getBytes(StandardCharsets.UTF_8);
        SecretStore store = new AesGcmSecretStore(masterKey, "local-v1");
        EncryptedSecret encrypted = store.encrypt(plaintext, CONTEXT);
        SecretContext otherContext = new SecretContext("tenant-2", "model-endpoint", "endpoint-1");

        assertThrows(SecretStoreException.class, () -> store.decrypt(encrypted, otherContext));
        Arrays.fill(masterKey, (byte) 0);
        Arrays.fill(plaintext, (byte) 0);
    }

    @Test
    void rejectsTamperedCiphertext() {
        byte[] masterKey = randomBytes(32);
        byte[] plaintext = "credential-value".getBytes(StandardCharsets.UTF_8);
        SecretStore store = new AesGcmSecretStore(masterKey, "local-v1");
        EncryptedSecret encrypted = store.encrypt(plaintext, CONTEXT);
        byte[] tamperedCiphertext = encrypted.ciphertext();
        tamperedCiphertext[0] ^= 1;
        EncryptedSecret tampered = new EncryptedSecret(
                encrypted.version(),
                encrypted.keyId(),
                encrypted.wrappedDataKeyNonce(),
                encrypted.wrappedDataKey(),
                encrypted.payloadNonce(),
                tamperedCiphertext);

        assertThrows(SecretStoreException.class, () -> store.decrypt(tampered, CONTEXT));
        Arrays.fill(masterKey, (byte) 0);
        Arrays.fill(plaintext, (byte) 0);
    }

    @Test
    void protectsInternalCiphertextWithDefensiveCopies() {
        byte[] masterKey = randomBytes(32);
        byte[] plaintext = "credential-value".getBytes(StandardCharsets.UTF_8);
        SecretStore store = new AesGcmSecretStore(masterKey, "local-v1");
        EncryptedSecret encrypted = store.encrypt(plaintext, CONTEXT);
        byte[] externalCopy = encrypted.ciphertext();
        externalCopy[0] ^= 1;

        byte[] decrypted = store.decrypt(encrypted, CONTEXT);

        assertArrayEquals(plaintext, decrypted);
        Arrays.fill(masterKey, (byte) 0);
        Arrays.fill(plaintext, (byte) 0);
        Arrays.fill(decrypted, (byte) 0);
        Arrays.fill(externalCopy, (byte) 0);
    }

    @Test
    void loadsBase64MasterKeyFromMountedFile(@TempDir Path tempDir) throws Exception {
        byte[] masterKey = randomBytes(32);
        Path keyFile = tempDir.resolve("master-key");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(masterKey), StandardCharsets.UTF_8);
        byte[] plaintext = "credential-value".getBytes(StandardCharsets.UTF_8);

        SecretStore store = AesGcmSecretStore.fromBase64File(keyFile, "local-v1");
        EncryptedSecret encrypted = store.encrypt(plaintext, CONTEXT);
        byte[] decrypted = store.decrypt(encrypted, CONTEXT);

        assertArrayEquals(plaintext, decrypted);
        Arrays.fill(masterKey, (byte) 0);
        Arrays.fill(plaintext, (byte) 0);
        Arrays.fill(decrypted, (byte) 0);
    }

    @Test
    void rejectsInvalidMasterKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new AesGcmSecretStore(new byte[16], "local-v1"));
    }

    private static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        new SecureRandom().nextBytes(value);
        return value;
    }
}
