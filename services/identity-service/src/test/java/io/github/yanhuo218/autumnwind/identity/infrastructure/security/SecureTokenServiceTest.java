package io.github.yanhuo218.autumnwind.identity.infrastructure.security;

import io.github.yanhuo218.autumnwind.identity.domain.security.IssuedToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureTokenServiceTest {

    @Test
    void 签发高熵URL安全Token且只持久化Hash() {
        SecureTokenService service = new SecureTokenService();

        IssuedToken first = service.issue();
        IssuedToken second = service.issue();

        assertEquals(43, first.rawValue().length());
        assertTrue(first.rawValue().matches("^[A-Za-z0-9_-]+$"));
        assertEquals(64, first.hash().length());
        assertEquals(first.hash(), service.hash(first.rawValue()));
        assertNotEquals(first.rawValue(), second.rawValue());
        assertNotEquals(first.hash(), second.hash());
        assertFalse(first.toString().contains(first.rawValue()));
    }

    @Test
    void 拒绝为空的Token() {
        SecureTokenService service = new SecureTokenService();

        assertThrows(IllegalArgumentException.class, () -> service.hash(" "));
    }
}
