package io.github.yanhuo218.autumnwind.inference.integration;

import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeClient;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeLimits;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderRequest;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
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

    private final Scenario scenario;
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile ValidatedTarget lastTarget;
    private volatile byte[] requestJson;
    private volatile byte[] apiKeySnapshot;
    private volatile byte[] apiKeyReference;

    FakeOpenAiProvider(Scenario scenario) {
        this.scenario = Objects.requireNonNull(scenario, "Fake Provider 场景不能为空。");
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
