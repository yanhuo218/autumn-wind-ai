package io.github.yanhuo218.autumnwind.gateway.identity;

import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtRequest;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentitySessionClientTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String CORRELATION_ID = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
    private final AtomicReference<UpstreamResponse> upstream = new AtomicReference<>();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicReference<ServiceJwtRequest> issuedRequest = new AtomicReference<>();
    private DisposableServer identityServer;
    private IdentitySessionClient client;

    @BeforeEach
    void setUp() {
        upstream.set(json(HttpStatus.OK, activeResponse(NOW.plusSeconds(60))));
        identityServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> request.receive().aggregate().asString()
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            captured.set(new CapturedRequest(request.method().name(), request.uri(),
                                    request.requestHeaders().get(HttpHeaders.AUTHORIZATION), body));
                            UpstreamResponse current = upstream.get();
                            response.status(current.status().value());
                            response.header(HttpHeaders.CONTENT_TYPE, current.contentType());
                            if (current.delayMillis() > 0) {
                                return response.sendString(Mono.just(current.body()).delaySubscription(
                                        java.time.Duration.ofMillis(current.delayMillis()))).then();
                            }
                            return response.sendString(Mono.just(current.body())).then();
                        }))
                .bindNow();
        ServiceJwtIssuer issuer = request -> {
            issuedRequest.set(request);
            return "service-token";
        };
        client = IdentitySessionClient.webClientBacked(
                WebClient.builder().baseUrl("http://127.0.0.1:" + identityServer.port()).build(),
                issuer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        if (identityServer != null) {
            identityServer.disposeNow();
        }
    }

    @Test
    void 活动会话使用最小权限JWT并返回可信身份() {
        var principal = client.introspect("opaque-session-value", CORRELATION_ID).block();

        assertEquals("/internal/v1/auth/session-introspections", captured.get().path());
        assertEquals("POST", captured.get().method());
        assertEquals("Bearer service-token", captured.get().authorization());
        assertTrue(captured.get().body().contains("sessionValue"));
        assertEquals("identity-service", issuedRequest.get().audience());
        assertEquals(java.util.Set.of("identity.session.introspect"), issuedRequest.get().scopes());
        assertEquals(null, issuedRequest.get().actorUserId());
        assertEquals("USER", principal.role());
        assertEquals(NOW.plusSeconds(60), principal.expiresAt());
        assertFalse(new SessionIntrospectionRequest("opaque-session-value").toString().contains("opaque-session-value"));
    }

    @Test
    void inactive过期和非活动账户映射为无效会话() {
        assertGatewayError(json(HttpStatus.OK, "{\"active\":false}"), GatewayErrorCode.INVALID_SESSION);
        assertGatewayError(json(HttpStatus.OK, activeResponse(NOW.minusSeconds(1))), GatewayErrorCode.INVALID_SESSION);
        assertGatewayError(json(HttpStatus.OK, """
                {"active":true,"userId":"4c184ec5-9127-4f43-a4b9-662d5e38846b","role":"USER","accountStatus":"DISABLED","expiresAt":"2026-07-19T00:01:00Z"}
                """), GatewayErrorCode.INVALID_SESSION);
    }

    @Test
    void 字段缺失非法JSON和错误媒体类型映射为协议错误() {
        assertGatewayError(json(HttpStatus.OK, "{\"active\":true}"), GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR);
        assertGatewayError(json(HttpStatus.OK, "not-json"), GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR);
        upstream.set(new UpstreamResponse(HttpStatus.OK, MediaType.TEXT_PLAIN_VALUE,
                activeResponse(NOW.plusSeconds(60)), 0));
        assertGatewayError(GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR);
    }

    @Test
    void applicationJson空正文映射为协议错误() {
        assertGatewayError(json(HttpStatus.OK, ""), GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR);
    }

    @Test
    void active缺失或为null映射为协议错误() {
        assertGatewayError(json(HttpStatus.OK, """
                {"userId":"4c184ec5-9127-4f43-a4b9-662d5e38846b","role":"USER","accountStatus":"ACTIVE","expiresAt":"2026-07-19T00:01:00Z"}
                """), GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR);
        assertGatewayError(json(HttpStatus.OK, """
                {"active":null,"userId":"4c184ec5-9127-4f43-a4b9-662d5e38846b","role":"USER","accountStatus":"ACTIVE","expiresAt":"2026-07-19T00:01:00Z"}
                """), GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR);
    }

    @Test
    void 两秒超时映射为身份服务不可用() {
        upstream.set(new UpstreamResponse(HttpStatus.OK, MediaType.APPLICATION_JSON_VALUE,
                activeResponse(NOW.plusSeconds(60)), 2100));

        assertGatewayError(GatewayErrorCode.IDENTITY_UNAVAILABLE);
    }

    private void assertGatewayError(UpstreamResponse response, GatewayErrorCode expected) {
        upstream.set(response);
        assertGatewayError(expected);
    }

    private void assertGatewayError(GatewayErrorCode expected) {
        GatewayException error = assertThrows(GatewayException.class,
                () -> client.introspect("opaque-session-value", CORRELATION_ID).block());
        assertEquals(expected, error.errorCode());
        assertFalse(error.getMessage().contains("opaque-session-value"));
    }

    private static UpstreamResponse json(HttpStatus status, String body) {
        return new UpstreamResponse(status, MediaType.APPLICATION_JSON_VALUE, body, 0);
    }

    private static String activeResponse(Instant expiresAt) {
        return """
                {"active":true,"userId":"4c184ec5-9127-4f43-a4b9-662d5e38846b","role":"USER","accountStatus":"ACTIVE","expiresAt":"%s"}
                """.formatted(expiresAt);
    }

    private record UpstreamResponse(HttpStatus status, String contentType, String body, long delayMillis) {
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }
}
