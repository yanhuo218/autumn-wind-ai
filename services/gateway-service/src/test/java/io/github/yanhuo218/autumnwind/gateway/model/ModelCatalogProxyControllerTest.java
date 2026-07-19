package io.github.yanhuo218.autumnwind.gateway.model;

import io.github.yanhuo218.autumnwind.gateway.configuration.GatewayDownstreamProperties;
import io.github.yanhuo218.autumnwind.gateway.configuration.GatewayWebClientConfiguration;
import io.github.yanhuo218.autumnwind.gateway.security.GatewaySessionAuthenticationWebFilter;
import io.github.yanhuo218.autumnwind.gateway.security.GatewayUserPrincipal;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtRequest;
import io.github.yanhuo218.autumnwind.gateway.web.CorrelationIdWebFilter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogProxyControllerTest {

    private static final String CORRELATION_ID = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
    private static final UUID ACTOR_USER_ID = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");

    private final List<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();
    private final List<ServiceJwtRequest> issuedRequests = new CopyOnWriteArrayList<>();
    private volatile UpstreamResponse upstreamResponse;
    private DisposableServer modelRegistryServer;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        upstreamResponse = json(HttpStatus.OK, "[{\"modelId\":\"model-1\"}]");
        modelRegistryServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> request.receive().aggregate().asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .flatMap(body -> {
                            capturedRequests.add(new CapturedRequest(request.method().name(), request.uri(),
                                    copyHeaders(request.requestHeaders()), body));
                            UpstreamResponse upstream = upstreamResponse;
                            response.status(upstream.status().value());
                            upstream.headers().forEach((name, values) ->
                                    values.forEach(value -> response.addHeader(name, value)));
                            Mono<byte[]> payload = Mono.just(upstream.body());
                            if (upstream.delayMillis() > 0) {
                                payload = payload.delaySubscription(java.time.Duration.ofMillis(upstream.delayMillis()));
                            }
                            return response.sendByteArray(payload).then();
                        }))
                .bindNow();
        client = clientFor("http://127.0.0.1:" + modelRegistryServer.port());
    }

    @AfterEach
    void tearDown() {
        if (modelRegistryServer != null) {
            modelRegistryServer.disposeNow();
        }
    }

    @Test
    void 可信身份使用最小权限JWT且不转发浏览器身份Header() throws InterruptedException {
        client.get()
                .uri("/api/v1/model-registry/models?actorUserId=00000000-0000-0000-0000-000000000000")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .header(HttpHeaders.COOKIE, "AW_SESSION=opaque-session-value")
                .header("X-CSRF-TOKEN", "csrf-value")
                .header(HttpHeaders.AUTHORIZATION, "Bearer browser-token")
                .header("X-Actor-User-Id", "forged-actor")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .expectBody().json("[{\"modelId\":\"model-1\"}]");

        CapturedRequest request = awaitRequest();
        assertEquals("GET", request.method());
        assertEquals("/api/v1/model-registry/models", request.path());
        assertEquals("Bearer service-token-1", request.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals(MediaType.APPLICATION_JSON_VALUE, request.headers().getFirst(HttpHeaders.ACCEPT));
        assertEquals(CORRELATION_ID, request.headers().getFirst(CorrelationIdWebFilter.HEADER_NAME));
        assertNull(request.headers().getFirst(HttpHeaders.COOKIE));
        assertNull(request.headers().getFirst("X-CSRF-TOKEN"));
        assertNull(request.headers().getFirst("X-Actor-User-Id"));
        assertNull(request.headers().getFirst("X-User-Role"));
        assertFalse(request.headers().getFirst(HttpHeaders.AUTHORIZATION).contains("browser-token"));
        assertEquals(1, issuedRequests.size());
        assertEquals("model-registry-service", issuedRequests.getFirst().audience());
        assertEquals(java.util.Set.of("model-registry.model.read"), issuedRequests.getFirst().scopes());
        assertEquals(ACTOR_USER_ID, issuedRequests.getFirst().actorUserId());
    }

    @Test
    void 每次模型目录请求均签发新的ServiceJWT() throws InterruptedException {
        response().exchange().expectStatus().isOk();
        response().exchange().expectStatus().isOk();

        CapturedRequest first = awaitRequest();
        CapturedRequest second = awaitRequest();
        assertNotEquals(first.headers().getFirst(HttpHeaders.AUTHORIZATION),
                second.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals(2, issuedRequests.size());
    }

    @Test
    void 缺失可信身份返回公开401且不调用下游() {
        WebTestClient withoutPrincipal = clientWithoutPrincipal("http://127.0.0.1:" + modelRegistryServer.port());

        withoutPrincipal.get().uri("/api/v1/model-registry/models")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .header("X-Actor-User-Id", ACTOR_USER_ID.toString())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-GATEWAY-AUTH-0001")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);

        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void 合法Registry业务错误保留状态JSON正文和允许的响应Header() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Retry-After", "30");
        headers.setCacheControl("no-store");
        headers.set("X-Registry-Internal", "must-not-forward");
        headers.setLocation(URI.create("https://model-registry.internal/error"));
        upstreamResponse = new UpstreamResponse(HttpStatus.FORBIDDEN, headers, """
                {"code":"AW-MODEL_REGISTRY-FORBIDDEN-0001","message":"禁止访问。","correlationId":"registry-correlation","fieldErrors":[]}
                """.getBytes(StandardCharsets.UTF_8), 0);

        response().exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("Retry-After", "30")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().doesNotExist("X-Registry-Internal")
                .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-MODEL_REGISTRY-FORBIDDEN-0001")
                .jsonPath("$.message").isEqualTo("禁止访问。");
    }

    @Test
    void 缺失fieldErrors的合法Registry业务错误仍可透传() {
        upstreamResponse = new UpstreamResponse(HttpStatus.FORBIDDEN, MediaType.APPLICATION_JSON,
                "{\"code\":\"AW-MODEL_REGISTRY-FORBIDDEN-0001\",\"message\":\"禁止访问。\",\"correlationId\":\"01JZ8M4A7X4S6NR2YQF1D9K3CP\"}"
                        .getBytes(StandardCharsets.UTF_8), 0);

        response().exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-MODEL_REGISTRY-FORBIDDEN-0001");
    }

    @Test
    void OpenAPI未声明状态即使错误信封合法也映射502() {
        for (HttpStatus status : List.of(
                HttpStatus.FOUND, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.TOO_MANY_REQUESTS)) {
            upstreamResponse = new UpstreamResponse(status, MediaType.APPLICATION_JSON,
                    "{\"code\":\"AW-MODEL_REGISTRY-INTERNAL-0001\",\"message\":\"错误。\",\"correlationId\":\"01JZ8M4A7X4S6NR2YQF1D9K3CP\"}"
                            .getBytes(StandardCharsets.UTF_8), 0);

            assertDependencyProtocolError();
        }
    }

    @Test
    void 空message仍是合法Registry错误并原状态透传() {
        upstreamResponse = new UpstreamResponse(HttpStatus.FORBIDDEN, MediaType.APPLICATION_JSON,
                "{\"code\":\"AW-MODEL_REGISTRY-FORBIDDEN-0001\",\"message\":\"\",\"correlationId\":\"01JZ8M4A7X4S6NR2YQF1D9K3CP\"}"
                        .getBytes(StandardCharsets.UTF_8), 0);

        response().exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("");
    }

    @Test
    void trailingJSONToken映射502() {
        upstreamResponse = new UpstreamResponse(HttpStatus.OK, MediaType.APPLICATION_JSON,
                "[] {}".getBytes(StandardCharsets.UTF_8), 0);

        assertDependencyProtocolError();
    }

    @Test
    void 重复对象键映射502() {
        upstreamResponse = new UpstreamResponse(HttpStatus.OK, MediaType.APPLICATION_JSON,
                "[{\"modelId\":\"first\",\"modelId\":\"second\"}]".getBytes(StandardCharsets.UTF_8), 0);

        assertDependencyProtocolError();
    }

    @Test
    void 错误码或关联ID不符合RegistryOpenAPI约束时映射502() {
        upstreamResponse = new UpstreamResponse(HttpStatus.BAD_REQUEST, MediaType.APPLICATION_JSON,
                "{\"code\":\"BAD-CODE\",\"message\":\"请求错误。\",\"correlationId\":\"short\",\"fieldErrors\":[]}"
                        .getBytes(StandardCharsets.UTF_8), 0);

        assertDependencyProtocolError();
    }

    @Test
    void fieldErrors包含非对象元素时映射502() {
        upstreamResponse = new UpstreamResponse(HttpStatus.BAD_REQUEST, MediaType.APPLICATION_JSON,
                "{\"code\":\"AW-MODEL_REGISTRY-VALIDATION-0001\",\"message\":\"请求错误。\",\"correlationId\":\"01JZ8M4A7X4S6NR2YQF1D9K3CP\",\"fieldErrors\":[\"bad\"]}"
                        .getBytes(StandardCharsets.UTF_8), 0);

        assertDependencyProtocolError();
    }

    @Test
    void 非法UTF8的成功数组映射502() {
        upstreamResponse = new UpstreamResponse(HttpStatus.OK, MediaType.APPLICATION_JSON,
                new byte[]{'[', '"', (byte) 0xC3, '"', ']'}, 0);

        assertDependencyProtocolError();
    }

    @Test
    void 非法错误结构和错误媒体类型映射为不泄露下游正文的502() {
        upstreamResponse = new UpstreamResponse(HttpStatus.BAD_REQUEST, MediaType.APPLICATION_JSON,
                "{\"untrusted\":\"https://model-registry.internal/detail\"}".getBytes(StandardCharsets.UTF_8), 0);

        assertDependencyProtocolError();

        upstreamResponse = new UpstreamResponse(HttpStatus.BAD_REQUEST, MediaType.TEXT_PLAIN,
                "https://model-registry.internal/detail".getBytes(StandardCharsets.UTF_8), 0);
        assertDependencyProtocolError();
    }

    @Test
    void 超过一MiB的响应映射为公开502() {
        upstreamResponse = new UpstreamResponse(HttpStatus.OK, MediaType.APPLICATION_JSON, new byte[1024 * 1024 + 1], 0);

        assertDependencyProtocolError();
    }

    @Test
    void 连接失败与五秒超时映射为公开503且不泄露地址() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        assertUnavailable(clientFor("http://127.0.0.1:" + unusedPort));

        upstreamResponse = new UpstreamResponse(HttpStatus.OK, MediaType.APPLICATION_JSON,
                "[]".getBytes(StandardCharsets.UTF_8), 5_100);
        assertUnavailable(client);
    }

    private void assertDependencyProtocolError() {
        EntityExchangeResult<byte[]> response = response().exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-GATEWAY-DEPENDENCY-0003")
                .returnResult();
        String body = new String(response.getResponseBody(), StandardCharsets.UTF_8);
        assertFalse(body.contains("model-registry.internal"));
    }

    private void assertUnavailable(WebTestClient target) {
        EntityExchangeResult<byte[]> response = target.get().uri("/api/v1/model-registry/models")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-GATEWAY-DEPENDENCY-0002")
                .returnResult();
        String body = new String(response.getResponseBody(), StandardCharsets.UTF_8);
        assertFalse(body.contains("127.0.0.1"));
    }

    private WebTestClient.RequestHeadersSpec<?> response() {
        return client.get().uri("/api/v1/model-registry/models")
                .header(CorrelationIdWebFilter.HEADER_NAME, CORRELATION_ID);
    }

    private CapturedRequest awaitRequest() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (!capturedRequests.isEmpty()) {
                return capturedRequests.removeFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("下游应收到模型目录请求。");
    }

    private WebTestClient clientFor(String baseUrl) {
        GatewayWebClientConfiguration configuration = new GatewayWebClientConfiguration();
        ServiceJwtIssuer issuer = request -> {
            issuedRequests.add(request);
            return "service-token-" + issuedRequests.size();
        };
        ModelRegistryProxyClient proxyClient = configuration.modelRegistryProxyClient(
                configuration.modelRegistryWebClient(new GatewayDownstreamProperties(
                        URI.create("https://identity.example"), URI.create(baseUrl))), issuer, new ObjectMapper());
        return WebTestClient.bindToController(new ModelCatalogProxyController(proxyClient, new GatewayErrorResponseWriter()))
                .webFilter(new CorrelationIdWebFilter())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(GatewaySessionAuthenticationWebFilter.AUTHENTICATED_USER_ATTRIBUTE,
                            new GatewayUserPrincipal(ACTOR_USER_ID, "USER", Instant.parse("2026-07-19T01:00:00Z")));
                    return chain.filter(exchange);
                })
                .configureClient()
                .responseTimeout(Duration.ofSeconds(8))
                .build();
    }

    private WebTestClient clientWithoutPrincipal(String baseUrl) {
        GatewayWebClientConfiguration configuration = new GatewayWebClientConfiguration();
        ModelRegistryProxyClient proxyClient = configuration.modelRegistryProxyClient(
                configuration.modelRegistryWebClient(new GatewayDownstreamProperties(
                        URI.create("https://identity.example"), URI.create(baseUrl))), request -> "unused", new ObjectMapper());
        return WebTestClient.bindToController(new ModelCatalogProxyController(proxyClient, new GatewayErrorResponseWriter()))
                .webFilter(new CorrelationIdWebFilter())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(8))
                .build();
    }

    private static UpstreamResponse json(HttpStatus status, String body) {
        return new UpstreamResponse(status, MediaType.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8), 0);
    }

    private static HttpHeaders copyHeaders(io.netty.handler.codec.http.HttpHeaders source) {
        HttpHeaders target = new HttpHeaders();
        source.forEach(header -> target.add(header.getKey(), header.getValue()));
        return target;
    }

    private record CapturedRequest(String method, String path, HttpHeaders headers, byte[] body) {
    }

    private record UpstreamResponse(HttpStatus status, HttpHeaders headers, byte[] body, long delayMillis) {

        private UpstreamResponse(HttpStatus status, MediaType mediaType, byte[] body, long delayMillis) {
            this(status, new HttpHeaders(), body, delayMillis);
            headers.setContentType(mediaType);
        }
    }
}
