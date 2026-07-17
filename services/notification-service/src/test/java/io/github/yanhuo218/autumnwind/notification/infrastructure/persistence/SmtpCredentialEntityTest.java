package io.github.yanhuo218.autumnwind.notification.infrastructure.persistence;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class SmtpCredentialEntityTest {

    @Test
    void 在持久化字段与加密值之间防御性转换() {
        EncryptedSecret encrypted = encryptedSecret();
        SmtpCredentialEntity entity = SmtpCredentialEntity.create(
                UUID.randomUUID(),
                encrypted,
                Instant.parse("2026-07-18T00:00:00Z")
        );

        EncryptedSecret restored = entity.toEncryptedSecret();

        assertEquals(encrypted.version(), restored.version());
        assertEquals(encrypted.keyId(), restored.keyId());
        assertArrayEquals(encrypted.ciphertext(), restored.ciphertext());
        assertNotSame(encrypted.ciphertext(), restored.ciphertext());
    }

    private static EncryptedSecret encryptedSecret() {
        return new EncryptedSecret(1, "local-v1", new byte[12], new byte[48], new byte[12], new byte[16]);
    }
}
