package io.github.yanhuo218.autumnwind.inference.integration;

import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import io.github.yanhuo218.autumnwind.inference.transport.PinnedAddressResolverGroup;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeClient;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeLimits;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderRequest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeOpenAiProvider implements ProviderExchangeClient {

    enum Scenario {
        NORMAL_NON_STREAM,
        SSE,
        RATE_LIMITED,
        BAD_GATEWAY,
        SERVICE_UNAVAILABLE,
        GATEWAY_TIMEOUT,
        SLOW_STREAM,
        NO_RESPONSE,
        MALFORMED_SSE,
        CONNECTION_FAILURE
    }

    private volatile Scenario scenario;
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile ValidatedTarget lastTarget;
    private volatile byte[] requestJson;
    private volatile byte[] apiKeySnapshot;
    private volatile byte[] apiKeyReference;
    private volatile DisposableServer server;
    private volatile SslContext clientTlsContext;
    private volatile CountDownLatch requestObserved = new CountDownLatch(0);
    private final AtomicBoolean streamCancelled = new AtomicBoolean();

    FakeOpenAiProvider(Scenario scenario) {
        this.scenario = Objects.requireNonNull(scenario, "Fake Provider 场景不能为空。");
    }

    void useScenario(Scenario value) {
        scenario = Objects.requireNonNull(value, "Fake Provider 场景不能为空。");
        streamCancelled.set(false);
        requestObserved = new CountDownLatch(server == null ? 0 : 1);
    }

    void startHttps(SslContext serverTlsContext, SslContext clientTlsContext) {
        this.clientTlsContext = Objects.requireNonNull(clientTlsContext, "TLS 客户端上下文不能为空。");
        requestObserved = new CountDownLatch(1);
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .protocol(HttpProtocol.HTTP11)
                .secure(spec -> spec.sslContext(Objects.requireNonNull(serverTlsContext, "TLS 服务端上下文不能为空。")))
                .handle((request, response) -> {
                    callCount.incrementAndGet();
                    String authorization = request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION);
                    apiKeySnapshot = authorization == null ? null : authorization.getBytes(StandardCharsets.US_ASCII);
                    requestObserved.countDown();
                    if (!"Bearer PLACEHOLDER_API_KEY_NOT_FOR_REAL_USE".equals(authorization)) {
                        return response.status(401).send();
                    }
                    return request.receive().aggregate().asByteArray().flatMap(bytes -> {
                        requestJson = Arrays.copyOf(bytes, bytes.length);
                        return sendResponse(response);
                    });
                })
                .bindNow();
    }

    String baseUrl() {
        if (server == null) {
            throw new IllegalStateException("Fake Provider 尚未启动。");
        }
        return "https://provider.invalid:" + server.port() + "/v1";
    }

    void awaitRequest() throws InterruptedException {
        if (!requestObserved.await(3, TimeUnit.SECONDS)) {
            throw new AssertionError("Fake Provider 未收到请求。");
        }
    }

    void assertNoPlatformIdentifiers(UUID owner, UUID model) {
        String request = requestJson == null ? "" : new String(requestJson, StandardCharsets.UTF_8);
        if (request.contains(owner.toString()) || request.contains(model.toString())) {
            throw new AssertionError("Provider 请求包含平台内部标识。");
        }
    }

    void assertPlaceholderAuthorization() {
        String authorization = apiKeySnapshot == null ? null : new String(apiKeySnapshot, StandardCharsets.US_ASCII);
        if (!"Bearer PLACEHOLDER_API_KEY_NOT_FOR_REAL_USE".equals(authorization)) {
            throw new AssertionError("Provider Authorization 不符合占位凭据约束。");
        }
    }

    boolean streamCancelled() {
        return streamCancelled.get();
    }

    void closeServer() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
    }

    @Override
    public Flux<ProviderFrame> exchange(
            ValidatedTarget target,
            ProviderRequest request,
            ProviderExchangeLimits limits
    ) {
        callCount.incrementAndGet();
        lastTarget = Objects.requireNonNull(target, "校验后的目标不能为空。");
        Objects.requireNonNull(request, "Provider 请求不能为空。");
        requestJson = Arrays.copyOf(request.body(), request.body().length);
        apiKeyReference = request.apiKey();
        apiKeySnapshot = Arrays.copyOf(request.apiKey(), request.apiKey().length);

        if (server != null) {
            return httpsExchange(target, request);
        }
        return switch (scenario) {
            case NORMAL_NON_STREAM -> frames(200, """
                    {"choices":[{"message":{"content":"回答占位符"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}
                    """);
            case SSE -> frames(200,
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"推理占位符\"}}]}\n\n",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"回答占位符\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\n",
                    "data: [DONE]\n\n");
            case RATE_LIMITED -> frames(429, "provider-rate-limit-detail-placeholder");
            case BAD_GATEWAY -> frames(502, "provider-bad-gateway-detail-placeholder");
            case SERVICE_UNAVAILABLE -> frames(503, "provider-unavailable-detail-placeholder");
            case GATEWAY_TIMEOUT -> frames(504, "provider-timeout-detail-placeholder");
            case SLOW_STREAM -> Flux.concat(
                    frames(200, "data: {\"choices\":[{\"delta\":{\"content\":\"部分占位符\"}}]}\n\n"),
                    Flux.never());
            case NO_RESPONSE -> Flux.never();
            case MALFORMED_SSE -> frames(200, "data: malformed-provider-detail-placeholder\n\n");
            case CONNECTION_FAILURE -> Flux.error(new IllegalStateException("fake-connection-placeholder"));
        };
    }

    int callCount() {
        return callCount.get();
    }

    ValidatedTarget lastTarget() {
        return lastTarget;
    }

    byte[] requestJson() {
        return copy(requestJson);
    }

    byte[] apiKeySnapshot() {
        return copy(apiKeySnapshot);
    }

    byte[] apiKeyReference() {
        return apiKeyReference;
    }

    private Flux<ProviderFrame> httpsExchange(ValidatedTarget target, ProviderRequest request) {
        URI targetUri = target.uri();
        return Flux.using(
                () -> new PinnedAddressResolverGroup(List.of(java.net.InetAddress.getLoopbackAddress())),
                resolver -> HttpClient.create()
                        .resolver(resolver)
                        .secure(spec -> spec.sslContext(clientTlsContext))
                        .headers(headers -> {
                            headers.set(HttpHeaderNames.AUTHORIZATION,
                                    "Bearer " + new String(request.apiKey(), StandardCharsets.US_ASCII));
                            headers.set(HttpHeaderNames.ACCEPT, "application/json");
                            headers.set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                        })
                        .post()
                        .uri(targetUri.toString())
                        .send(Mono.fromSupplier(() -> Unpooled.wrappedBuffer(request.body())))
                        .response((response, content) -> content.map(buffer -> {
                            byte[] bytes = new byte[buffer.readableBytes()];
                            buffer.readBytes(bytes);
                            return new ProviderFrame(response.status().code(), bytes);
                        })),
                PinnedAddressResolverGroup::close);
    }

    private Mono<Void> sendResponse(reactor.netty.http.server.HttpServerResponse response) {
        return switch (scenario) {
            case NORMAL_NON_STREAM -> response.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    .sendString(Mono.just("""
                            {"choices":[{"message":{"content":"回答占位符"},"finish_reason":"stop"}],
                            "usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}
                            """))
                    .then();
            case SSE -> response.header(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
                    .sendString(Flux.just(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"回答占位符\"},\"finish_reason\":\"stop\"}],"
                                    + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\n",
                            "data: [DONE]\n\n"))
                    .then();
            case RATE_LIMITED -> response.status(429).sendString(Mono.just("provider-rate-limit-detail-placeholder")).then();
            case BAD_GATEWAY -> response.status(502).sendString(Mono.just("provider-bad-gateway-detail-placeholder")).then();
            case SERVICE_UNAVAILABLE -> response.status(503).sendString(Mono.just("provider-unavailable-detail-placeholder")).then();
            case GATEWAY_TIMEOUT -> response.status(504).sendString(Mono.just("provider-timeout-detail-placeholder")).then();
            case SLOW_STREAM -> response.header(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
                    .sendString(Flux.concat(
                            Mono.just("data: {\"choices\":[{\"delta\":{\"content\":\"部分占位符\"}}]}\n\n"),
                            Mono.<String>never())
                            .doOnCancel(() -> streamCancelled.set(true)))
                    .then();
            case NO_RESPONSE -> response.sendString(Mono.never()).then();
            case MALFORMED_SSE -> response.header(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
                    .sendString(Mono.just("data: malformed-provider-detail-placeholder\n\n")).then();
            case CONNECTION_FAILURE -> response.sendString(Mono.error(new IllegalStateException("fake-connection-placeholder"))).then();
        };
    }

    @Override
    public String toString() {
        return "FakeOpenAiProvider[scenario=" + scenario
                + ", callCount=" + callCount
                + ", target=<REDACTED>, requestJson=<REDACTED>, apiKey=<REDACTED>]";
    }

    private static Flux<ProviderFrame> frames(int status, String... bodies) {
        return Flux.fromArray(bodies)
                .map(body -> new ProviderFrame(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
