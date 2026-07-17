package io.github.yanhuo218.autumnwind.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Argon2PasswordHasherTest {

    @Test
    void 使用Argon2id生成带随机盐的密码哈希() {
        Argon2PasswordHasher hasher = new Argon2PasswordHasher();

        String first = hasher.hash("correct horse battery staple");
        String second = hasher.hash("correct horse battery staple");

        assertTrue(first.startsWith("$argon2id$"));
        assertNotEquals(first, second);
        assertTrue(hasher.matches("correct horse battery staple", first));
        assertFalse(hasher.matches("incorrect password", first));
    }
}
