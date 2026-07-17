package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
public class SessionCookieFactory {

    public static final String COOKIE_NAME = "AW_SESSION";

    private final Clock clock;

    public SessionCookieFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    public ResponseCookie create(String rawSessionToken, Instant expiresAt) {
        Objects.requireNonNull(rawSessionToken, "原始会话 Token 不能为空。");
        Objects.requireNonNull(expiresAt, "会话过期时间不能为空。");
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("不能为已过期会话创建 Cookie。");
        }
        return base(rawSessionToken).maxAge(maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax");
    }
}
