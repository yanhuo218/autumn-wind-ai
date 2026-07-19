package io.github.yanhuo218.autumnwind.gateway.security;

import io.github.yanhuo218.autumnwind.gateway.identity.IdentitySessionClient;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver;
import org.springframework.web.server.session.DefaultWebSessionManager;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewaySessionAuthenticationWebFilterTest {

    @Test
    void 仅模型列表请求重新校验会话并写入可信身份() {
        AtomicInteger calls = new AtomicInteger();
        GatewayUserPrincipal principal = new GatewayUserPrincipal(
                UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b"), "USER", Instant.parse("2026-07-19T00:01:00Z"));
        IdentitySessionClient client = (session, correlationId) -> {
            calls.incrementAndGet();
            return Mono.just(principal);
        };
        GatewaySessionAuthenticationWebFilter filter = filter(client);
        AtomicReference<Object> seen = new AtomicReference<>();

        filter.filter(exchange("/api/v1/model-registry/models", "opaque-session-value"), current -> {
            seen.set(current.getAttribute(GatewaySessionAuthenticationWebFilter.AUTHENTICATED_USER_ATTRIBUTE));
            return Mono.empty();
        }).block();
        filter.filter(exchange("/api/v1/model-registry/models", "opaque-session-value"), current -> Mono.empty()).block();

        assertEquals(principal, seen.get());
        assertEquals(2, calls.get());
    }

    @Test
    void 非模型列表路由不触发会话校验() {
        AtomicInteger calls = new AtomicInteger();
        GatewaySessionAuthenticationWebFilter filter = filter((session, correlationId) -> {
            calls.incrementAndGet();
            return Mono.error(new AssertionError("不应调用Identity"));
        });

        filter.filter(exchange("/api/v1/auth/session", null), current -> Mono.empty()).block();

        assertEquals(0, calls.get());
    }

    @Test
    void 缺失Cookie和下游错误按公开错误码关闭请求() {
        GatewaySessionAuthenticationWebFilter invalidSession = filter((session, correlationId) -> Mono.empty());
        ServerWebExchange missingCookie = exchange("/api/v1/model-registry/models", null);

        invalidSession.filter(missingCookie, current -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, missingCookie.getResponse().getStatusCode());
        assertTrue(((MockServerHttpResponse) missingCookie.getResponse()).getBodyAsString().block()
                .contains("AW-GATEWAY-AUTH-0001"));

        GatewaySessionAuthenticationWebFilter unavailable = filter((session, correlationId) -> Mono.error(
                new GatewayException(GatewayErrorCode.IDENTITY_UNAVAILABLE, "身份服务不可用。")));
        ServerWebExchange failed = exchange("/api/v1/model-registry/models", "opaque-session-value");
        unavailable.filter(failed, current -> Mono.empty()).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failed.getResponse().getStatusCode());
        String body = ((MockServerHttpResponse) failed.getResponse()).getBodyAsString().block();
        assertTrue(body.contains("AW-GATEWAY-DEPENDENCY-0001"));
        assertFalse(body.contains("opaque-session-value"));
    }

    @Test
    void 认证成功后的下游异常原样传播且不由身份过滤器写响应() {
        GatewaySessionAuthenticationWebFilter filter = filter((session, correlationId) -> Mono.just(
                new GatewayUserPrincipal(
                        UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b"),
                        "USER",
                        Instant.parse("2026-07-19T00:01:00Z"))));
        ServerWebExchange exchange = exchange("/api/v1/model-registry/models", "opaque-session-value");
        IllegalStateException downstreamError = new IllegalStateException("下游处理失败");

        IllegalStateException propagated = assertThrows(IllegalStateException.class,
                () -> filter.filter(exchange, current -> Mono.error(downstreamError)).block());

        assertSame(downstreamError, propagated);
        assertNull(exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().isCommitted());
    }

    private static GatewaySessionAuthenticationWebFilter filter(IdentitySessionClient client) {
        return new GatewaySessionAuthenticationWebFilter(
                new SessionCookieExtractor(), client, new GatewayErrorResponseWriter());
    }

    private static ServerWebExchange exchange(String path, String session) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (session != null) {
            request.cookie(new HttpCookie("AW_SESSION", session));
        }
        return new DefaultServerWebExchange(request.build(), new MockServerHttpResponse(),
                new DefaultWebSessionManager(), ServerCodecConfigurer.create(),
                new AcceptHeaderLocaleContextResolver());
    }
}
