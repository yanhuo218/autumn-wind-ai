package io.github.yanhuo218.autumnwind.inference.integration;

import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent;
import io.github.yanhuo218.autumnwind.inference.chat.OpenAiChatCompletionsAdapter;
import io.github.yanhuo218.autumnwind.inference.credentials.EndpointCredentialResolver;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.security.secrets.AesGcmSecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceGatewayIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID OWNER_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID MODEL_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000099");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final UUID CREDENTIAL_ID = UUID.fromString("a24a3063-1e16-49dd-b1a8-6edb9d477810");
    private static final String CORRELATION_ID = "correlation-placeholder-integration";
    private static final byte[] API_KEY_PLACEHOLDER =
            "PLACEHOLDER_API_KEY_NOT_FOR_REAL_USE".getBytes(StandardCharsets.US_ASCII);

    @Test
    void 真实SecretStore解密且Provider完成后原始明文字节被清零() throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.NORMAL_NON_STREAM);
        Fixture fixture = fixture(provider, List.of(publicAddress()));

        List<InferenceEvent> events = fixture.adapter().infer(command(false), fixture.target())
                .collectList().block(Duration.ofSeconds(2));

        assertEquals(List.of("start", "text_delta", "usage", "done"), eventTypes(events));
        assertTrue(Arrays.equals(API_KEY_PLACEHOLDER, provider.apiKeySnapshot()));
        assertFalse(allZero(provider.apiKeySnapshot()));
        assertEventuallyZero(provider);
    }

    @Test
    void 请求只使用Registry模型快照并包含SystemPrompt消息和Usage选项() throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.SSE);
        Fixture fixture = fixture(provider, List.of(publicAddress()));

        fixture.adapter().infer(command(true), fixture.target()).blockLast(Duration.ofSeconds(2));

        JsonNode request = MAPPER.readTree(provider.requestJson());
        assertEquals("provider-model-from-registry-placeholder", request.path("model").stringValue());
        assertEquals(2, request.path("messages").size());
        assertEquals("system", request.path("messages").get(0).path("role").stringValue());
        assertEquals("系统文本占位符", request.path("messages").get(0).path("content").stringValue());
        assertEquals("user", request.path("messages").get(1).path("role").stringValue());
        assertEquals("用户文本占位符", request.path("messages").get(1).path("content").stringValue());
        assertTrue(request.path("stream").booleanValue());
        assertTrue(request.path("stream_options").path("include_usage").booleanValue());
        String providerRequest = new String(provider.requestJson(), StandardCharsets.UTF_8);
        assertFalse(providerRequest.contains(OWNER_ID.toString()));
        assertFalse(providerRequest.contains(MODEL_ID.toString()));
    }

    @Test
    void SSE通过真实Decoder标准化且不暴露原始帧() throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.SSE);
        Fixture fixture = fixture(provider, List.of(publicAddress()));

        List<InferenceEvent> events = fixture.adapter().infer(command(true), fixture.target())
                .collectList().block(Duration.ofSeconds(2));

        assertEquals(List.of("start", "reasoning", "text_delta", "usage", "done"), eventTypes(events));
        assertEquals("推理占位符", assertInstanceOf(InferenceEvent.Reasoning.class, events.get(1)).delta());
        assertEquals("回答占位符", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(2)).delta());
        InferenceEvent.Usage usage = assertInstanceOf(InferenceEvent.Usage.class, events.get(3));
        assertEquals(2, usage.promptTokens());
        assertEquals(3, usage.completionTokens());
        assertEquals(5, usage.totalTokens());
        assertFalse(events.toString().contains("data:"));
        assertFalse(events.toString().contains("choices"));
    }

    @ParameterizedTest
    @CsvSource({
            "RATE_LIMITED,PROVIDER_RATE_LIMITED",
            "BAD_GATEWAY,PROVIDER_UNAVAILABLE",
            "SERVICE_UNAVAILABLE,PROVIDER_UNAVAILABLE",
            "GATEWAY_TIMEOUT,PROVIDER_UNAVAILABLE"
    })
    void 可重试状态仅在事件前最多重试两次(String scenarioName, String expectedCode) throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.valueOf(scenarioName));
        Fixture fixture = fixture(provider, List.of(publicAddress()));

        List<InferenceEvent> events = fixture.adapter().infer(command(true), fixture.target())
                .collectList().block(Duration.ofSeconds(2));

        assertEquals(3, provider.callCount());
        assertEquals(3, fixture.dnsCount().get());
        assertEquals(3, fixture.secretStore().decryptCount());
        InferenceEvent.Error error = singleError(events);
        assertEquals(expectedCode, error.code().name());
        assertTrue(error.retryable());
    }

    @Test
    void 流开始后的畸形SSE只产生稳定错误且不重试() throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.MALFORMED_SSE);
        Fixture fixture = fixture(provider, List.of(publicAddress()));

        List<InferenceEvent> events = fixture.adapter().infer(command(true), fixture.target())
                .collectList().block(Duration.ofSeconds(2));

        assertEquals(List.of("start", "error"), eventTypes(events));
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.get(1));
        assertEquals(InferenceEvent.ErrorCode.PROVIDER_RESPONSE_INVALID, error.code());
        assertFalse(error.retryable());
        assertEquals(1, provider.callCount());
        assertFalse(events.toString().contains("malformed-provider-detail-placeholder"));
    }

    @Test
    void 慢流使用Registry快照超时且流开始后不重试() throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.SLOW_STREAM);
        Fixture fixture = fixture(provider, List.of(publicAddress()));
        InferenceTarget target = withRequestTimeout(fixture.target(), 1);

        List<InferenceEvent> events = fixture.adapter().infer(command(true), target)
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(List.of("start", "text_delta", "error"), eventTypes(events));
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.get(2));
        assertEquals(InferenceEvent.ErrorCode.CONNECTION_FAILED, error.code());
        assertFalse(error.retryable());
        assertEquals(1, provider.callCount());
        assertEventuallyZero(provider);
    }

    @Test
    void 首帧前absoluteDeadline终止后不重试且最终清零凭据() throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.NO_RESPONSE);
        Fixture fixture = fixture(provider, List.of(publicAddress()));
        InferenceTarget target = withRequestTimeout(fixture.target(), 1);

        List<InferenceEvent> events = fixture.adapter().infer(command(true), target)
                .collectList().block(Duration.ofSeconds(5));

        InferenceEvent.Error error = singleError(events);
        assertEquals(InferenceEvent.ErrorCode.CONNECTION_FAILED, error.code());
        assertFalse(error.retryable());
        assertEquals(1, provider.callCount());
        assertEquals(1, fixture.dnsCount().get());
        assertEquals(1, fixture.secretStore().decryptCount());
        assertEventuallyZero(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "MIXED"})
    void 私网或混合DNS在Provider调用前拒绝(String addressScenario) throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.SSE);
        List<InetAddress> addresses = "PRIVATE".equals(addressScenario)
                ? List.of(privateAddress())
                : List.of(publicAddress(), privateAddress());
        Fixture fixture = fixture(provider, addresses);

        List<InferenceEvent> events = fixture.adapter().infer(command(true), fixture.target())
                .collectList().block(Duration.ofSeconds(2));

        InferenceEvent.Error error = singleError(events);
        assertEquals(InferenceEvent.ErrorCode.TARGET_REJECTED, error.code());
        assertFalse(error.retryable());
        assertEquals(0, provider.callCount());
        assertEquals(1, fixture.dnsCount().get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MODEL", "CAPABILITY"})
    void 快照不匹配在解密和Provider调用前拒绝(String mismatch) throws Exception {
        FakeOpenAiProvider provider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.SSE);
        Fixture fixture = fixture(provider, List.of(publicAddress()));
        ChatInferenceCommand command = command(true);
        InferenceTarget target = fixture.target();
        if ("OWNER".equals(mismatch)) {
            command = command(OTHER_ID, MODEL_ID, true);
        } else if ("MODEL".equals(mismatch)) {
            command = command(OWNER_ID, OTHER_ID, true);
        } else {
            target = withCapabilities(target, new InferenceTarget.Capabilities(
                    "RESPONSES", Set.of("TEXT"), "TEXT", true, true, true, 8192, 1024));
        }

        List<InferenceEvent> events = fixture.adapter().infer(command, target)
                .collectList().block(Duration.ofSeconds(2));

        InferenceEvent.Error error = singleError(events);
        assertEquals(InferenceEvent.ErrorCode.TARGET_REJECTED, error.code());
        assertEquals(0, fixture.secretStore().decryptCount());
        assertEquals(0, fixture.dnsCount().get());
        assertEquals(0, provider.callCount());
    }

    private static Fixture fixture(FakeOpenAiProvider provider, List<InetAddress> addresses) {
        byte[] masterKey = new byte[32];
        CountingSecretStore secretStore;
        try {
            new SecureRandom().nextBytes(masterKey);
            secretStore = new CountingSecretStore(
                    new AesGcmSecretStore(masterKey, "integration-key-placeholder"));
        } finally {
            Arrays.fill(masterKey, (byte) 0);
        }

        byte[] plaintext = Arrays.copyOf(API_KEY_PLACEHOLDER, API_KEY_PLACEHOLDER.length);
        EncryptedSecret encrypted;
        try {
            encrypted = secretStore.encrypt(plaintext, new SecretContext(
                    OWNER_ID.toString(), "model-endpoint-api-key", ENDPOINT_ID.toString()));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        InferenceTarget target = new InferenceTarget(
                OWNER_ID,
                MODEL_ID,
                "provider-model-from-registry-placeholder",
                7,
                ENDPOINT_ID,
                URI.create("https://provider.invalid/v1"),
                "OPENAI_COMPATIBLE",
                30,
                11,
                new InferenceTarget.Capabilities(
                        "CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT", true, true, true, 8192, 1024),
                CREDENTIAL_ID,
                encrypted);
        AtomicInteger dnsCount = new AtomicInteger();
        OutboundTargetPolicy targetPolicy = new OutboundTargetPolicy(host -> {
            dnsCount.incrementAndGet();
            return addresses;
        }, new PublicAddressPolicy());
        OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(
                MAPPER, targetPolicy, new EndpointCredentialResolver(secretStore), provider);
        return new Fixture(adapter, target, secretStore, dnsCount);
    }

    private static ChatInferenceCommand command(boolean stream) {
        return command(OWNER_ID, MODEL_ID, stream);
    }

    private static ChatInferenceCommand command(UUID ownerId, UUID modelId, boolean stream) {
        return new ChatInferenceCommand(
                ownerId,
                modelId,
                List.of(new ChatInferenceCommand.Message("user", "用户文本占位符")),
                "系统文本占位符",
                0.5,
                256,
                stream,
                CORRELATION_ID);
    }

    private static InferenceTarget withCapabilities(
            InferenceTarget target,
            InferenceTarget.Capabilities capabilities
    ) {
        return new InferenceTarget(
                target.ownerUserId(), target.modelId(), target.providerModelId(), target.modelVersion(),
                target.endpointId(), target.endpointBaseUrl(), target.endpointProtocol(),
                target.endpointRequestTimeoutSeconds(), target.endpointVersion(), capabilities,
                target.credentialId(), target.credential());
    }

    private static InferenceTarget withRequestTimeout(InferenceTarget target, int timeoutSeconds) {
        return new InferenceTarget(
                target.ownerUserId(), target.modelId(), target.providerModelId(), target.modelVersion(),
                target.endpointId(), target.endpointBaseUrl(), target.endpointProtocol(), timeoutSeconds,
                target.endpointVersion(), target.capabilities(), target.credentialId(), target.credential());
    }

    private static InferenceEvent.Error singleError(List<InferenceEvent> events) {
        if (events.size() == 2) {
            assertInstanceOf(InferenceEvent.Start.class, events.getFirst());
            return assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        }
        assertEquals(1, events.size());
        return assertInstanceOf(InferenceEvent.Error.class, events.getFirst());
    }

    private static List<String> eventTypes(List<InferenceEvent> events) {
        return events.stream().map(InferenceEvent::type).toList();
    }

    private static InetAddress publicAddress() throws Exception {
        return InetAddress.getByAddress(new byte[]{11, 0, 0, 1});
    }

    private static InetAddress privateAddress() throws Exception {
        return InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static void assertEventuallyZero(FakeOpenAiProvider provider) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!allZero(provider.apiKeyReference()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(allZero(provider.apiKeyReference()));
    }

    private record Fixture(
            OpenAiChatCompletionsAdapter adapter,
            InferenceTarget target,
            CountingSecretStore secretStore,
            AtomicInteger dnsCount
    ) {
    }

    private static final class CountingSecretStore implements SecretStore {

        private final SecretStore delegate;
        private final AtomicInteger decryptCount = new AtomicInteger();

        private CountingSecretStore(SecretStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
            return delegate.encrypt(plaintext, context);
        }

        @Override
        public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
            decryptCount.incrementAndGet();
            return delegate.decrypt(encryptedSecret, context);
        }

        private int decryptCount() {
            return decryptCount.get();
        }
    }
}
