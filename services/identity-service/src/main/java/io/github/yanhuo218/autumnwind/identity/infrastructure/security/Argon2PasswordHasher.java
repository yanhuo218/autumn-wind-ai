package io.github.yanhuo218.autumnwind.identity.infrastructure.security;

import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.util.Objects;

public final class Argon2PasswordHasher implements PasswordHasher {

    private final Argon2PasswordEncoder encoder;

    public Argon2PasswordHasher() {
        this(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    Argon2PasswordHasher(Argon2PasswordEncoder encoder) {
        this.encoder = Objects.requireNonNull(encoder, "密码编码器不能为空。");
    }

    @Override
    public String hash(CharSequence password) {
        requirePassword(password);
        return encoder.encode(password);
    }

    @Override
    public boolean matches(CharSequence password, String encodedPassword) {
        requirePassword(password);
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        return encoder.matches(password, encodedPassword);
    }

    private static void requirePassword(CharSequence password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空。");
        }
    }
}
