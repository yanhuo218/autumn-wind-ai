package io.github.yanhuo218.autumnwind.gateway.identity;

import io.github.yanhuo218.autumnwind.gateway.configuration.GatewayDownstreamProperties;
import io.github.yanhuo218.autumnwind.gateway.configuration.GatewayWebClientConfiguration;
import io.github.yanhuo218.autumnwind.gateway.web.CorrelationIdWebFilter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityAuthProxyControllerTest {

    private static final String CORRELATION_ID = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
    private static final String TEST_COOKIE = "AW_SESSION=opaque-test-value";
    private static final String RESPONSE_COOKIE = "AW_SESSION=opaque-response-value; Path=/; HttpOnly; Secure; SameSite=Lax";
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final byte[] RESPONSE_BODY = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    private final BlockingQueue<CapturedRequest> receivedRequests = new LinkedBlockingQueue<>();
    private DisposableServer identityServer;
    private WebTestClient client;
    private volatile UpstreamResponse upstreamResponse;

    @BeforeEach
    void setUp() {
        upstreamResponse = new UpstreamResponse(HttpStatus.CREATED.value(), responseHeaders(), RESPONSE_BODY);
        identityServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> request.receive().aggregate().asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .flatMap(body -> {
                            receivedRequests.add(new CapturedRequest(
                                    request.method().name(),
                                    request.uri(),
                                    copyHeaders(request.requestHeaders()),
                                    body));
                            UpstreamResponse upstream = upstreamResponse;
                            response.status(upstream.status());
                            upstream.headers().forEach((name, values) ->
                                    values.forEach(value -> response.addHeader(name, value)));
                            return response.sendByteArray(Mono.just(upstream.body())).then();
                        }))
                .bindNow();
        client = clientFor("http://127.0.0.1:" + identityServer.port());
    }

    @AfterEach
    void tearDown() {
        if (identityServer != null) {
            identityServer.disposeNow();
        }
    }

    @ParameterizedTest
    @MethodSource("authenticationRoutes")
    void 六条固定认证路由只转发白名单Header并保留安全响应(AuthRoute route) throws InterruptedException {
        byte[] requestBody = route.acceptsBody()
                ? "{\"email\":\"user@example.test\",\"password\":\"placeholder\"}".getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        EntityExchangeResult<byte[]> response = requestFor(route, requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .expectHeader().valueEquals(HttpHeaders.SET_COOKIE, RESPONSE_COOKIE)
                .expectHeader().valueEquals("X-CSRF-TOKEN", "downstream-csrf-value")
                .expectHeader().doesNotExist("X-Identity-Internal")
                .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                .expectBody()
                .returnResult();

        assertArrayEquals(RESPONSE_BODY, response.getResponseBody());
        CapturedRequest captured = receivedRequests.poll(2, TimeUnit.SECONDS);
        assertTrue(captured != null, "下游应收到代理请求。");
        assertEquals(route.method().name(), captured.method());
        assertEquals(route.path(), captured.path());
        assertArrayEquals(requestBody, captured.body());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, captured.headers().getFirst(HttpHeaders.ACCEPT));
        assertEquals(MediaType.APPLICATION_JSON_VALUE, captured.headers().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals(TEST_COOKIE, captured.headers().getFirst(HttpHeaders.COOKIE));
        assertEquals("csrf-request-value", captured.headers().getFirst("X-CSRF-TOKEN"));
        assertEquals(CORRELATION_ID, captured.headers().getFirst(CorrelationIdWebFilter.HEADER_NAME));
        assertEquals(TRACEPARENT, captured.headers().getFirst("traceparent"));
        assertNull(captured.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(captured.headers().getFirst("Forwarded"));
        assertNull(captured.headers().getFirst("X-Forwarded-For"));
        assertNull(captured.headers().getFirst("X-Actor-User-Id"));
        assertNull(captured.headers().getFirst("X-User-Role"));
        assertNull(captured.headers().getFirst("X-Identity-Internal"));
    }

    @Test
    void 超过十六KiB的认证正文返回公开413且不调用下游() throws InterruptedException {
        byte[] oversizedBody = new byte[17 * 1024];

        client.post()
                .uri("/api/v1/auth/sessions")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(oversizedBody)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectHeader().valueEquals(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-GATEWAY-VALIDATION-0001")
                .jsonPath("$.message").exists()
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);

        assertNull(receivedRequests.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void 下游注销返回204空正文时保留状态和清理Cookie() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, RESPONSE_COOKIE);
        upstreamResponse = new UpstreamResponse(HttpStatus.NO_CONTENT.value(), headers, new byte[0]);

        client.delete()
                .uri("/api/v1/auth/session")
                .header(HttpHeaders.COOKIE, TEST_COOKIE)
                .header("X-CSRF-TOKEN", "csrf-request-value")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals(HttpHeaders.SET_COOKIE, RESPONSE_COOKIE)
                .expectBody().isEmpty();
    }

    @Test
    void 超过一MiB的下游响应映射为公开依赖错误() {
        upstreamResponse = new UpstreamResponse(HttpStatus.OK.value(), responseHeaders(), new byte[1024 * 1024 + 1]);

        client.get()
                .uri("/api/v1/auth/csrf")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().valueEquals(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-GATEWAY-DEPENDENCY-0003")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);
    }

    @Test
    void 不可用下游只返回公开错误不暴露下游地址或请求数据() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        WebTestClient unavailableClient = clientFor("http://127.0.0.1:" + unusedPort);

        EntityExchangeResult<byte[]> response = unavailableClient.get()
                .uri("/api/v1/auth/csrf")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-GATEWAY-DEPENDENCY-0001")
                .jsonPath("$.message").exists()
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID)
                .returnResult();

        String body = new String(response.getResponseBody(), StandardCharsets.UTF_8);
        assertFalse(body.contains("127.0.0.1"));
        assertFalse(body.contains(TEST_COOKIE));
    }

    private WebTestClient clientFor(String identityBaseUrl) {
        GatewayWebClientConfiguration configuration = new GatewayWebClientConfiguration();
        GatewayDownstreamProperties properties = new GatewayDownstreamProperties(
                URI.create(identityBaseUrl), URI.create("https://model-registry.example"));
        IdentityAuthProxyClient proxyClient = configuration.identityAuthProxyClient(
                configuration.identityWebClient(properties));
        return WebTestClient.bindToController(new IdentityAuthProxyController(
                        proxyClient, new GatewayErrorResponseWriter()))
                .webFilter(new CorrelationIdWebFilter())
                .build();
    }

    private WebTestClient.RequestHeadersSpec<?> requestFor(AuthRoute route, byte[] body) {
        WebTestClient.RequestBodySpec request = client.method(route.method())
                .uri(route.path())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, TEST_COOKIE)
                .header("X-CSRF-TOKEN", "csrf-request-value")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .header("traceparent", TRACEPARENT)
                .header(HttpHeaders.AUTHORIZATION, "browser-credential-not-for-forwarding")
                .header("Forwarded", "for=198.51.100.10")
                .header("X-Forwarded-For", "198.51.100.10")
                .header("X-Actor-User-Id", "actor-not-for-forwarding")
                .header("X-User-Role", "role-not-for-forwarding")
                .header("X-Identity-Internal", "internal-not-for-forwarding");
        return route.acceptsBody() ? request.bodyValue(body) : request;
    }

    private static Stream<AuthRoute> authenticationRoutes() {
        return Stream.of(
                new AuthRoute(HttpMethod.GET, "/api/v1/auth/csrf", false),
                new AuthRoute(HttpMethod.GET, "/api/v1/auth/registration-options", false),
                new AuthRoute(HttpMethod.POST, "/api/v1/auth/registrations", true),
                new AuthRoute(HttpMethod.POST, "/api/v1/auth/sessions", true),
                new AuthRoute(HttpMethod.GET, "/api/v1/auth/session", false),
                new AuthRoute(HttpMethod.DELETE, "/api/v1/auth/session", false));
    }

    private static HttpHeaders responseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.SET_COOKIE, RESPONSE_COOKIE);
        headers.add("X-CSRF-TOKEN", "downstream-csrf-value");
        headers.add("X-Identity-Internal", "must-not-be-returned");
        headers.add(HttpHeaders.LOCATION, "https://identity.internal.example/redirect");
        return headers;
    }

    private static HttpHeaders copyHeaders(io.netty.handler.codec.http.HttpHeaders source) {
        HttpHeaders target = new HttpHeaders();
        source.forEach(header -> target.add(header.getKey(), header.getValue()));
        return target;
    }

    private record AuthRoute(HttpMethod method, String path, boolean acceptsBody) {
    }

    private record CapturedRequest(String method, String path, HttpHeaders headers, byte[] body) {
    }

    private record UpstreamResponse(int status, HttpHeaders headers, byte[] body) {
    }
}
