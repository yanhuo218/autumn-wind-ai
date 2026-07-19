package io.github.yanhuo218.autumnwind.gateway.security;

import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.List;
import java.util.Objects;

public final class SessionCookieExtractor {

    private static final String COOKIE_NAME = "AW_SESSION";

    public String extract(ServerHttpRequest request) {
        Objects.requireNonNull(request, "HTTP 请求不能为空。");
        List<HttpCookie> cookies = request.getCookies().get(COOKIE_NAME);
        if (cookies == null || cookies.size() != 1) {
            throw invalidSession();
        }
        String value = cookies.getFirst().getValue();
        if (value == null || value.isBlank()) {
            throw invalidSession();
        }
        return value;
    }

    private static GatewayException invalidSession() {
        return new GatewayException(GatewayErrorCode.INVALID_SESSION, "会话无效或已过期。");
    }
}
