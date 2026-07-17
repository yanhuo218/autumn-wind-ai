package io.github.yanhuo218.autumnwind.identity.infrastructure.security;

import io.github.yanhuo218.autumnwind.identity.domain.security.IssuedToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class SecureTokenService {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;

    public SecureTokenService() {
        this(new SecureRandom());
    }

    SecureTokenService(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "安全随机数生成器不能为空。");
    }

    public IssuedToken issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        try {
            String rawValue = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return new IssuedToken(rawValue, hash(rawValue));
        }
        finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    public String hash(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Token 不能为空。");
        }
        byte[] rawBytes = rawValue.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawBytes));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256。", exception);
        }
        finally {
            java.util.Arrays.fill(rawBytes, (byte) 0);
        }
    }
}
