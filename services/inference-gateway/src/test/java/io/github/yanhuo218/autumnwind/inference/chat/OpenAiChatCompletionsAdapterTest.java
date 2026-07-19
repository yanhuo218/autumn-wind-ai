package io.github.yanhuo218.autumnwind.inference.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.yanhuo218.autumnwind.inference.credentials.EndpointCredentialResolver;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.inference.security.TargetPolicyException;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeClient;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderRequest;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import org.reactivestreams.Subscription;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatCompletionsAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID MODEL_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000099");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final UUID CREDENTIAL_ID = UUID.fromString("a24a3063-1e16-49dd-b1a8-6edb9d477810");
    private static final String CORRELATION_ID = "correlation-placeholder-0001";
    private static final String PLACEHOLDER_KEY = "PLACEHOLDER_API_KEY_NOT_FOR_REAL_USE";

    @Test
    void 六类事件实际JSON精确匹配Schema分支() throws Exception {
        String attemptId = "11111111-1111-4111-8111-111111111111";
        Map<InferenceEvent, JsonNode> expected = new LinkedHashMap<>();
        expected.put(new InferenceEvent.Start(attemptId), MAPPER.readTree(
                "{\"type\":\"start\",\"attemptId\":\"" + attemptId + "\"}"));
        expected.put(new InferenceEvent.Reasoning("reasoning-placeholder"), MAPPER.readTree(
                "{\"type\":\"reasoning\",\"delta\":\"reasoning-placeholder\"}"));
        expected.put(new InferenceEvent.TextDelta("text-placeholder"), MAPPER.readTree(
                "{\"type\":\"text_delta\",\"delta\":\"text-placeholder\"}"));
        expected.put(new InferenceEvent.Usage(2, null, 5), MAPPER.readTree(
                "{\"type\":\"usage\",\"promptTokens\":2,\"completionTokens\":null,\"totalTokens\":5}"));
        expected.put(new InferenceEvent.Error(InferenceEvent.ErrorCode.PROVIDER_ERROR, CORRELATION_ID, false), MAPPER.readTree(
                "{\"type\":\"error\",\"code\":\"PROVIDER_ERROR\",\"correlationId\":\""
                        + CORRELATION_ID + "\",\"retryable\":false}"));
        expected.put(new InferenceEvent.Done(null), MAPPER.readTree(
                "{\"type\":\"done\",\"finishReason\":null}"));
        JsonNode schema = MAPPER.readTree(Files.readString(Path.of(
                "..", "..", "contracts", "events", "inference-event.v1.schema.json")));

        for (Map.Entry<InferenceEvent, JsonNode> entry : expected.entrySet()) {
            JsonNode actual = MAPPER.valueToTree(entry.getKey());
            assertEquals(entry.getValue(), actual);

            JsonNode branch = schema.path("oneOf").valueStream()
                    .filter(candidate -> actual.path("type").stringValue()
                            .equals(candidate.path("properties").path("type").path("const").stringValue()))
                    .findFirst()
                    .orElseThrow();
            Set<String> actualFields = Set.copyOf(actual.propertyNames());
            Set<String> requiredFields = branch.path("required").valueStream()
                    .map(JsonNode::stringValue)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            assertEquals(requiredFields, actualFields);
            assertEquals(Set.copyOf(branch.path("properties").propertyNames()), actualFields);
            assertFalse(branch.path("additionalProperties").booleanValue());
        }

        JsonNode errorBranch = schema.path("oneOf").valueStream()
                .filter(candidate -> "error".equals(candidate.path("properties").path("type").path("const").stringValue()))
                .findFirst()
                .orElseThrow();
        Set<String> schemaErrorCodes = errorBranch.path("properties").path("code").path("enum").valueStream()
                .map(JsonNode::stringValue)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> javaErrorCodes = Arrays.stream(InferenceEvent.ErrorCode.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(schemaErrorCodes, javaErrorCodes);
    }

    @Test
    void 事件构造器拒绝Schema外值() {
        assertThrows(IllegalArgumentException.class, () -> new InferenceEvent.Start("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class, () -> new InferenceEvent.Usage(-1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new InferenceEvent.Usage(null, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new InferenceEvent.Usage(null, null, -1));
        assertThrows(IllegalArgumentException.class, () -> new InferenceEvent.Error(
                InferenceEvent.ErrorCode.PROVIDER_ERROR, "https://provider.invalid/private", false));
        assertThrows(IllegalArgumentException.class, () -> new InferenceEvent.Done("x".repeat(129)));
    }

    @Test
    void 流式请求结构化映射且providerModelId只能来自目标() throws Exception {
        AtomicReference<ProviderRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<URI> capturedUri = new AtomicReference<>();
        AtomicInteger dnsCount = new AtomicInteger();
        TrackingSecretStore secretStore = new TrackingSecretStore();
        ProviderExchangeClient client = (validated, request, limits) -> {
            capturedUri.set(validated.uri());
            capturedRequest.set(request);
            return Flux.just(frame(200, "data: [DONE]\n\n"));
        };
        OpenAiChatCompletionsAdapter adapter = adapter(client, secretStore, dnsCount, false);
        ChatInferenceCommand command = new ChatInferenceCommand(
                TENANT_ID,
                MODEL_ID,
                List.of(
                        new ChatInferenceCommand.Message("user", "用户文本占位符"),
                        new ChatInferenceCommand.Message("assistant", "助手文本占位符")),
                "系统提示占位符",
                0.7,
                256,
                true,
                CORRELATION_ID);

        List<InferenceEvent> events = adapter.infer(command, target(defaultCapabilities())).collectList().block();

        assertEquals(2, events.size());
        assertInstanceOf(InferenceEvent.Start.class, events.get(0));
        assertInstanceOf(InferenceEvent.Done.class, events.get(1));
        assertEquals(URI.create("https://provider.invalid:443/v1/chat/completions"), capturedUri.get());
        assertEquals(1, dnsCount.get());
        JsonNode body = MAPPER.readTree(capturedRequest.get().body());
        assertEquals("provider-model-from-target-placeholder", body.path("model").stringValue());
        assertEquals(true, body.path("stream").booleanValue());
        assertEquals(true, body.path("stream_options").path("include_usage").booleanValue());
        assertEquals(0.7, body.path("temperature").doubleValue());
        assertEquals(256, body.path("max_tokens").intValue());
        assertEquals(3, body.path("messages").size());
        assertEquals("system", body.path("messages").get(0).path("role").stringValue());
        assertEquals("系统提示占位符", body.path("messages").get(0).path("content").stringValue());
        assertEquals("user", body.path("messages").get(1).path("role").stringValue());
        assertEquals("assistant", body.path("messages").get(2).path("role").stringValue());
        assertFalse(capturedRequest.get().toString().contains(PLACEHOLDER_KEY));
        assertFalse(capturedRequest.get().toString().contains("系统提示占位符"));
        assertTrue(secretStore.allReleased());
    }

    @Test
    void 非流请求省略streamOptions与未提供的可选参数() throws Exception {
        AtomicReference<ProviderRequest> request = new AtomicReference<>();
        ProviderExchangeClient client = (validated, actual, limits) -> {
            request.set(actual);
            return Flux.just(frame(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        };

        List<InferenceEvent> events = adapter(client).infer(command(false), target(defaultCapabilities()))
                .collectList().block();

        JsonNode body = MAPPER.readTree(request.get().body());
        assertEquals(false, body.path("stream").booleanValue());
        assertFalse(body.has("stream_options"));
        assertFalse(body.has("temperature"));
        assertFalse(body.has("max_tokens"));
        assertEquals(3, events.size());
        assertInstanceOf(InferenceEvent.Start.class, events.get(0));
        assertEquals("ok", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(1)).delta());
        assertInstanceOf(InferenceEvent.Done.class, events.get(2));
    }

    @Test
    void 追加路径时保留HTTPSAuthority和编码后的基路径() {
        AtomicReference<URI> capturedUri = new AtomicReference<>();
        ProviderExchangeClient client = (validated, request, limits) -> {
            capturedUri.set(validated.uri());
            return Flux.just(frame(200, "data: [DONE]\n\n"));
        };
        InferenceTarget customTarget = target(
                defaultCapabilities(),
                URI.create("https://provider.invalid:8443/openai%2Ftenant?opaque=ignored"));

        adapter(client).infer(command(true), customTarget).collectList().block();

        assertEquals(URI.create("https://provider.invalid:8443/openai%2Ftenant/chat/completions"), capturedUri.get());
    }

    @Test
    void 拒绝租户模型角色参数和能力不匹配且不访问网络() {
        AtomicInteger exchanges = new AtomicInteger();
        ProviderExchangeClient client = (validated, request, limits) -> {
            exchanges.incrementAndGet();
            return Flux.error(new AssertionError("不应访问网络"));
        };
        OpenAiChatCompletionsAdapter adapter = adapter(client);
        List<ChatInferenceCommand> invalidCommands = List.of(
                withIdentity(OTHER_ID, MODEL_ID),
                withIdentity(TENANT_ID, OTHER_ID),
                commandWith(new ChatInferenceCommand.Message("system", "非法角色占位符"), null, null, true),
                commandWith(new ChatInferenceCommand.Message("user", "文本占位符"), -0.01, null, true),
                commandWith(new ChatInferenceCommand.Message("user", "文本占位符"), 2.01, null, true),
                commandWith(new ChatInferenceCommand.Message("user", "文本占位符"), null, 0, true),
                commandWith(new ChatInferenceCommand.Message("user", "文本占位符"), null, 1025, true));

        for (ChatInferenceCommand invalid : invalidCommands) {
            assertError(adapter.infer(invalid, target(defaultCapabilities())).collectList().block(),
                    "TARGET_REJECTED", false);
        }
        assertError(adapter.infer(command(true), target(capabilities("RESPONSES", Set.of("TEXT"), "TEXT", true, true)))
                .collectList().block(), "TARGET_REJECTED", false);
        assertError(adapter.infer(command(true), target(capabilities("CHAT_COMPLETIONS", Set.of("IMAGE"), "TEXT", true, true)))
                .collectList().block(), "TARGET_REJECTED", false);
        assertError(adapter.infer(command(true), target(capabilities("CHAT_COMPLETIONS", Set.of("TEXT"), "IMAGE", true, true)))
                .collectList().block(), "TARGET_REJECTED", false);
        assertError(adapter.infer(command(true), target(capabilities("CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT", false, true)))
                .collectList().block(), "TARGET_REJECTED", false);
        assertError(adapter.infer(commandWithSystemPrompt(), target(capabilities("CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT", true, false)))
                .collectList().block(), "TARGET_REJECTED", false);
        assertEquals(0, exchanges.get());
    }

    @ParameterizedTest
    @CsvSource({
            "401,PROVIDER_AUTHENTICATION_FAILED,false,1",
            "403,PROVIDER_AUTHENTICATION_FAILED,false,1",
            "400,PROVIDER_ERROR,false,1",
            "404,PROVIDER_ERROR,false,1",
            "422,PROVIDER_ERROR,false,1",
            "429,PROVIDER_RATE_LIMITED,true,3",
            "502,PROVIDER_UNAVAILABLE,true,3",
            "503,PROVIDER_UNAVAILABLE,true,3",
            "504,PROVIDER_UNAVAILABLE,true,3",
            "500,PROVIDER_ERROR,false,1"
    })
    void 状态码映射遵守有限重试边界(int status, String code, boolean retryable, int expectedAttempts) {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger dnsCount = new AtomicInteger();
        TrackingSecretStore secretStore = new TrackingSecretStore();
        ProviderExchangeClient client = (validated, request, limits) -> {
            attempts.incrementAndGet();
            return Flux.just(frame(status, "provider-error-body-placeholder"));
        };

        List<InferenceEvent> events = adapter(client, secretStore, dnsCount, false)
                .infer(command(true), target(defaultCapabilities())).collectList().block();

        assertError(events, code, retryable);
        assertEquals(expectedAttempts, attempts.get());
        assertEquals(expectedAttempts, dnsCount.get());
        assertEquals(expectedAttempts, secretStore.plaintexts.size());
        assertTrue(secretStore.allReleased());
    }

    @ParameterizedTest
    @CsvSource({
            "200,PROVIDER_RESPONSE_INVALID,false,1",
            "401,PROVIDER_AUTHENTICATION_FAILED,false,1",
            "429,PROVIDER_RATE_LIMITED,true,3"
    })
    void 空正文状态帧保持错误与重试语义(int status, String code, boolean retryable, int expectedAttempts) {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger dnsCount = new AtomicInteger();
        TrackingSecretStore secretStore = new TrackingSecretStore();
        ProviderExchangeClient client = (validated, request, limits) -> {
            attempts.incrementAndGet();
            return Flux.just(new ProviderFrame(status, new byte[0]));
        };

        List<InferenceEvent> events = adapter(client, secretStore, dnsCount, false)
                .infer(command(true), target(defaultCapabilities())).collectList().block();

        InferenceEvent.Error error;
        if (status == 200) {
            assertEquals(2, events.size());
            assertInstanceOf(InferenceEvent.Start.class, events.get(0));
            error = assertInstanceOf(InferenceEvent.Error.class, events.get(1));
        } else {
            assertEquals(2, events.size());
            assertInstanceOf(InferenceEvent.Start.class, events.get(0));
            error = assertInstanceOf(InferenceEvent.Error.class, events.get(1));
        }
        assertEquals(code, error.code().name());
        assertEquals(retryable, error.retryable());
        assertEquals(expectedAttempts, attempts.get());
        assertEquals(expectedAttempts, dnsCount.get());
        assertEquals(expectedAttempts, secretStore.plaintexts.size());
        assertTrue(secretStore.allReleased());
    }

    @Test
    void 首帧前连接失败最多重试两次且每次重新校验() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger dnsCount = new AtomicInteger();
        ProviderExchangeClient client = (validated, request, limits) -> {
            attempts.incrementAndGet();
            return Flux.error(new IllegalStateException("connection-sensitive-placeholder"));
        };

        List<InferenceEvent> events = adapter(client, new TrackingSecretStore(), dnsCount, false)
                .infer(command(true), target(defaultCapabilities())).collectList().block();

        assertError(events, "CONNECTION_FAILED", true);
        assertEquals(3, attempts.get());
        assertEquals(3, dnsCount.get());
        assertFalse(events.toString().contains("connection-sensitive-placeholder"));
    }

    @Test
    void SSRF策略拒绝不重试也不调用Provider() {
        AtomicInteger exchanges = new AtomicInteger();
        AtomicInteger dnsCount = new AtomicInteger();
        ProviderExchangeClient client = (validated, request, limits) -> {
            exchanges.incrementAndGet();
            return Flux.empty();
        };

        List<InferenceEvent> events = adapter(client, new TrackingSecretStore(), dnsCount, true)
                .infer(command(true), target(defaultCapabilities())).collectList().block();

        assertError(events, "TARGET_REJECTED", false);
        assertEquals(0, exchanges.get());
        assertEquals(1, dnsCount.get());
    }

    @Test
    void Provider异步SSRF拒绝保留目标错误且不重试() {
        AtomicInteger exchanges = new AtomicInteger();
        AtomicInteger dnsCount = new AtomicInteger();
        TrackingSecretStore secretStore = new TrackingSecretStore();
        ProviderExchangeClient client = (validated, request, limits) -> {
            exchanges.incrementAndGet();
            return Flux.error(new TargetPolicyException("异步目标策略占位诊断"));
        };

        List<InferenceEvent> events = adapter(client, secretStore, dnsCount, false)
                .infer(command(true), target(defaultCapabilities())).collectList().block();

        assertError(events, "TARGET_REJECTED", false);
        assertEquals(1, exchanges.get());
        assertEquals(1, dnsCount.get());
        assertEquals(1, secretStore.plaintexts.size());
        assertTrue(secretStore.allReleased());
        assertFalse(events.toString().contains("异步目标策略占位诊断"));
    }

    @Test
    void 解密依赖失败映射内部错误且不校验DNS() {
        SecretStore failingStore = new SecretStore() {
            @Override
            public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
                throw new IllegalStateException("credential-sensitive-placeholder");
            }
        };
        AtomicInteger dnsCount = new AtomicInteger();

        List<InferenceEvent> events = adapter((validated, request, limits) -> Flux.empty(),
                        failingStore, dnsCount, false)
                .infer(command(true), target(defaultCapabilities())).collectList().block();

        assertError(events, "INTERNAL_DEPENDENCY_ERROR", false);
        assertEquals(0, dnsCount.get());
        assertFalse(events.toString().contains("credential-sensitive-placeholder"));
    }

    @Test
    void 首个2xx后才发start且流开始后的错误只输出一个error不重试() {
        AtomicInteger attempts = new AtomicInteger();
        ProviderExchangeClient client = (validated, request, limits) -> {
            attempts.incrementAndGet();
            return Flux.concat(
                    Flux.just(frame(200, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")),
                    Flux.error(new IllegalStateException("after-start-sensitive-placeholder")));
        };

        List<InferenceEvent> events = adapter(client).infer(command(true), target(defaultCapabilities()))
                .collectList().block();

        assertEquals(3, events.size());
        InferenceEvent.Start start = assertInstanceOf(InferenceEvent.Start.class, events.get(0));
        assertFalse(start.attemptId().isBlank());
        UUID.fromString(start.attemptId());
        assertEquals("partial", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(1)).delta());
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.get(2));
        assertEquals("CONNECTION_FAILED", error.code().name());
        assertFalse(error.retryable());
        assertEquals(1, attempts.get());
        assertEquals(1, events.stream().filter(InferenceEvent.Error.class::isInstance).count());
        assertFalse(events.toString().contains("after-start-sensitive-placeholder"));
    }

    @Test
    void 状态2xx的畸形响应在start后输出安全响应错误() {
        ProviderExchangeClient client = (validated, request, limits) -> Flux.just(frame(200, "data: {bad-json}\n\n"));

        List<InferenceEvent> events = adapter(client).infer(command(true), target(defaultCapabilities()))
                .collectList().block();

        assertEquals(2, events.size());
        assertInstanceOf(InferenceEvent.Start.class, events.get(0));
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.get(1));
        assertEquals("PROVIDER_RESPONSE_INVALID", error.code().name());
        assertFalse(error.retryable());
    }

    @Test
    void 取消完整流时凭据数组立即清零() throws InterruptedException {
        TrackingSecretStore secretStore = new TrackingSecretStore();
        AtomicReference<byte[]> credentialBytes = new AtomicReference<>();
        ProviderExchangeClient client = (validated, request, limits) -> {
            credentialBytes.set(request.apiKey());
            return Flux.concat(
                    Flux.just(frame(200, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")),
                    Flux.never());
        };
        List<InferenceEvent> received = new ArrayList<>();
        CountDownLatch receivedEvents = new CountDownLatch(2);

        Disposable subscription = adapter(client, secretStore, new AtomicInteger(), false)
                .infer(command(true), target(defaultCapabilities()))
                .subscribe(event -> {
                    received.add(event);
                    receivedEvents.countDown();
                });

        assertTrue(receivedEvents.await(2, TimeUnit.SECONDS));
        assertEquals(2, received.size());
        assertFalse(allZero(credentialBytes.get()));
        subscription.dispose();
        assertTrue(allZero(credentialBytes.get()));
    }

    @Test
    void 仅消费同步start后取消不会触达Provider() {
        AtomicInteger exchanges = new AtomicInteger();
        List<InferenceEvent> received = new ArrayList<>();
        ProviderExchangeClient client = (validated, request, limits) -> {
            exchanges.incrementAndGet();
            return Flux.never();
        };

        adapter(client).infer(command(true), target(defaultCapabilities()))
                .subscribe(new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        request(1);
                    }

                    @Override
                    protected void hookOnNext(InferenceEvent event) {
                        received.add(event);
                        cancel();
                    }
                });

        assertEquals(1, received.size());
        assertInstanceOf(InferenceEvent.Start.class, received.getFirst());
        assertEquals(0, exchanges.get());
    }

    @Test
    void 消费服务商事件后取消会dispose上游并关闭凭据() throws InterruptedException {
        TrackingSecretStore secretStore = new TrackingSecretStore();
        AtomicReference<byte[]> credentialBytes = new AtomicReference<>();
        AtomicBoolean disposed = new AtomicBoolean();
        ProviderExchangeClient client = (validated, request, limits) -> {
            credentialBytes.set(request.apiKey());
            return Flux.concat(
                            Flux.just(frame(200, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")),
                            Flux.never())
                    .doOnCancel(() -> disposed.set(true));
        };
        List<InferenceEvent> received = new ArrayList<>();
        CountDownLatch receivedEvents = new CountDownLatch(2);

        Disposable subscription = adapter(client, secretStore, new AtomicInteger(), false)
                .infer(command(true), target(defaultCapabilities()))
                .subscribe(event -> {
                    received.add(event);
                    receivedEvents.countDown();
                });

        assertTrue(receivedEvents.await(2, TimeUnit.SECONDS));
        assertEquals(2, received.size());
        assertFalse(allZero(credentialBytes.get()));
        subscription.dispose();

        assertTrue(disposed.get());
        assertTrue(allZero(credentialBytes.get()));
        assertTrue(secretStore.allReleased());
    }

    @Test
    void 终态后晚到帧不会产生第二个终态或文本事件() {
        ProviderExchangeClient client = (validated, request, limits) -> Flux.just(
                frame(200, "data: [DONE]\n\n"),
                frame(200, "data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\n"));

        List<InferenceEvent> events = adapter(client).infer(command(true), target(defaultCapabilities()))
                .collectList().block();

        assertEquals(2, events.size());
        assertInstanceOf(InferenceEvent.Start.class, events.get(0));
        assertInstanceOf(InferenceEvent.Done.class, events.get(1));
    }

    @Test
    void 服务商响应超限不重试并输出稳定不可重试错误() {
        AtomicInteger attempts = new AtomicInteger();
        ProviderExchangeClient client = (validated, request, limits) -> {
            attempts.incrementAndGet();
            return Flux.error(new ProviderExchangeClient.ResponseLimitExceededException());
        };

        List<InferenceEvent> events = adapter(client).infer(command(true), target(defaultCapabilities()))
                .collectList().block();

        assertEquals(1, attempts.get());
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        assertEquals(InferenceEvent.ErrorCode.PROVIDER_RESPONSE_INVALID, error.code());
        assertFalse(error.retryable());
    }

    @Test
    void 持续帧不能把端点总超时重置为逐帧空闲超时() {
        AtomicInteger attempts = new AtomicInteger();
        ProviderExchangeClient client = (validated, request, limits) -> {
            attempts.incrementAndGet();
            return Flux.interval(Duration.ofMillis(100))
                    .map(ignored -> frame(200,
                            "data: {\"choices\":[{\"delta\":{\"content\":\"tick\"}}]}\n\n"));
        };

        List<InferenceEvent> events = adapter(client).infer(command(true), target(defaultCapabilities(), 1))
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(1, attempts.get());
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        assertEquals(InferenceEvent.ErrorCode.CONNECTION_FAILED, error.code());
        assertFalse(error.retryable());
    }

    @Test
    void 重试共享端点总超时deadline() {
        AtomicInteger attempts = new AtomicInteger();
        ProviderExchangeClient client = (validated, request, limits) -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.<ProviderFrame>error(new IllegalStateException("first-attempt-placeholder"))
                        .delaySubscription(Duration.ofMillis(600));
            }
            return Flux.never();
        };
        long started = System.nanoTime();

        List<InferenceEvent> events = adapter(client).infer(command(true), target(defaultCapabilities(), 1))
                .collectList().block(Duration.ofSeconds(3));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(2, attempts.get());
        assertTrue(elapsedMillis < 1_300, "重试不得获得新的完整超时窗口。");
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        assertEquals(InferenceEvent.ErrorCode.CONNECTION_FAILED, error.code());
        assertFalse(error.retryable());
    }

    @Test
    void 慢解密超过总时限时Provider未调用且晚到凭据清零() throws InterruptedException {
        AtomicInteger exchanges = new AtomicInteger();
        byte[] plaintext = PLACEHOLDER_KEY.getBytes(StandardCharsets.US_ASCII);
        CountDownLatch decryptReturned = new CountDownLatch(1);
        SecretStore slowStore = slowSecretStore(plaintext, decryptReturned);
        ProviderExchangeClient client = (validated, request, limits) -> {
            exchanges.incrementAndGet();
            return Flux.never();
        };

        List<InferenceEvent> events = adapter(client, slowStore, new AtomicInteger(), false)
                .infer(command(true), target(defaultCapabilities(), 1))
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(0, exchanges.get());
        assertStableDeadlineError(events);
        assertTrue(decryptReturned.await(2, TimeUnit.SECONDS));
        assertTrue(awaitCondition(() -> allZero(plaintext)));
    }

    @Test
    void 慢DNS超过总时限时Provider未调用且凭据清零() throws InterruptedException {
        AtomicInteger exchanges = new AtomicInteger();
        CountDownLatch resolverReturned = new CountDownLatch(1);
        TrackingSecretStore secretStore = new TrackingSecretStore();
        ProviderExchangeClient client = (validated, request, limits) -> {
            exchanges.incrementAndGet();
            return Flux.never();
        };

        List<InferenceEvent> events = adapter(client, secretStore, new AtomicInteger(), false,
                Duration.ofMillis(1_500), resolverReturned)
                .infer(command(true), target(defaultCapabilities(), 1))
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(0, exchanges.get());
        assertStableDeadlineError(events);
        assertTrue(resolverReturned.await(2, TimeUnit.SECONDS));
        assertTrue(awaitCondition(secretStore::allReleased));
    }

    @Test
    void 命令消息和错误事件字符串表示不泄露敏感占位文本() {
        ChatInferenceCommand command = new ChatInferenceCommand(
                TENANT_ID, MODEL_ID,
                List.of(new ChatInferenceCommand.Message("user", "sensitive-message-placeholder")),
                "sensitive-system-placeholder", null, null, true, CORRELATION_ID);
        ProviderExchangeClient client = (validated, request, limits) -> Flux.just(frame(401, "sensitive-provider-placeholder"));

        List<InferenceEvent> events = adapter(client).infer(command, target(defaultCapabilities())).collectList().block();

        String values = command + " " + command.messages().get(0) + " " + events;
        assertFalse(values.contains("sensitive-message-placeholder"));
        assertFalse(values.contains("sensitive-system-placeholder"));
        assertFalse(values.contains("sensitive-provider-placeholder"));
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        assertEquals(CORRELATION_ID, error.correlationId());
    }

    @Test
    void 关联ID拒绝可能暴露端点的URI式文本() {
        assertThrows(IllegalArgumentException.class, () -> new ChatInferenceCommand(
                TENANT_ID,
                MODEL_ID,
                List.of(new ChatInferenceCommand.Message("user", "请求文本占位符")),
                null,
                null,
                null,
                true,
                "https://provider.invalid/private"));
    }

    private static OpenAiChatCompletionsAdapter adapter(ProviderExchangeClient client) {
        return adapter(client, new TrackingSecretStore(), new AtomicInteger(), false);
    }

    private static OpenAiChatCompletionsAdapter adapter(
            ProviderExchangeClient client,
            SecretStore secretStore,
            AtomicInteger dnsCount,
            boolean privateAddress
    ) {
        return adapter(client, secretStore, dnsCount, privateAddress, Duration.ZERO, null);
    }

    private static OpenAiChatCompletionsAdapter adapter(
            ProviderExchangeClient client,
            SecretStore secretStore,
            AtomicInteger dnsCount,
            boolean privateAddress,
            Duration resolverDelay,
            CountDownLatch resolverReturned
    ) {
        OutboundTargetPolicy policy = new OutboundTargetPolicy(host -> {
            dnsCount.incrementAndGet();
            if (!resolverDelay.isZero()) {
                try {
                    Thread.sleep(resolverDelay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (resolverReturned != null) {
                        resolverReturned.countDown();
                    }
                }
            }
            return List.of(InetAddress.getByName(privateAddress ? "10.0.0.1" : "11.0.0.1"));
        }, new PublicAddressPolicy());
        return new OpenAiChatCompletionsAdapter(
                MAPPER,
                policy,
                new EndpointCredentialResolver(secretStore),
                client);
    }

    private static ChatInferenceCommand command(boolean stream) {
        return new ChatInferenceCommand(
                TENANT_ID,
                MODEL_ID,
                List.of(new ChatInferenceCommand.Message("user", "请求文本占位符")),
                null,
                null,
                null,
                stream,
                CORRELATION_ID);
    }

    private static ChatInferenceCommand commandWithSystemPrompt() {
        return new ChatInferenceCommand(
                TENANT_ID,
                MODEL_ID,
                List.of(new ChatInferenceCommand.Message("user", "请求文本占位符")),
                "系统文本占位符",
                null,
                null,
                true,
                CORRELATION_ID);
    }

    private static ChatInferenceCommand withIdentity(UUID tenantId, UUID modelId) {
        return new ChatInferenceCommand(
                tenantId,
                modelId,
                List.of(new ChatInferenceCommand.Message("user", "请求文本占位符")),
                null,
                null,
                null,
                true,
                CORRELATION_ID);
    }

    private static ChatInferenceCommand commandWith(
            ChatInferenceCommand.Message message,
            Double temperature,
            Integer maxOutputTokens,
            boolean stream
    ) {
        return new ChatInferenceCommand(
                TENANT_ID, MODEL_ID, List.of(message), null, temperature, maxOutputTokens, stream, CORRELATION_ID);
    }

    private static InferenceTarget target(InferenceTarget.Capabilities capabilities) {
        return target(capabilities, URI.create("https://provider.invalid/v1"), 30);
    }

    private static InferenceTarget target(InferenceTarget.Capabilities capabilities, int endpointTimeoutSeconds) {
        return target(capabilities, URI.create("https://provider.invalid/v1"), endpointTimeoutSeconds);
    }

    private static InferenceTarget target(InferenceTarget.Capabilities capabilities, URI endpointBaseUrl) {
        return target(capabilities, endpointBaseUrl, 30);
    }

    private static InferenceTarget target(
            InferenceTarget.Capabilities capabilities,
            URI endpointBaseUrl,
            int endpointTimeoutSeconds
    ) {
        return new InferenceTarget(
                TENANT_ID,
                MODEL_ID,
                "provider-model-from-target-placeholder",
                1,
                ENDPOINT_ID,
                endpointBaseUrl,
                "OPENAI_COMPATIBLE",
                endpointTimeoutSeconds,
                2,
                capabilities,
                CREDENTIAL_ID,
                new EncryptedSecret(1, "key-id-placeholder", new byte[12], new byte[48], new byte[12], new byte[16]));
    }

    private static InferenceTarget.Capabilities defaultCapabilities() {
        return capabilities("CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT", true, true);
    }

    private static InferenceTarget.Capabilities capabilities(
            String interfaceType,
            Set<String> inputs,
            String output,
            boolean streaming,
            boolean systemPrompt
    ) {
        return new InferenceTarget.Capabilities(
                interfaceType, inputs, output, streaming, systemPrompt, true, 8192, 1024);
    }

    private static ProviderFrame frame(int status, String data) {
        return new ProviderFrame(status, data.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertError(List<InferenceEvent> events, String code, boolean retryable) {
        if (events.size() == 2) {
            assertInstanceOf(InferenceEvent.Start.class, events.getFirst());
        } else {
            assertEquals(1, events.size());
        }
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        assertEquals("error", error.type());
        assertEquals(code, error.code().name());
        assertEquals(CORRELATION_ID, error.correlationId());
        assertEquals(retryable, error.retryable());
    }

    private static void assertStableDeadlineError(List<InferenceEvent> events) {
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        assertEquals(InferenceEvent.ErrorCode.CONNECTION_FAILED, error.code());
        assertFalse(error.retryable());
    }

    private static SecretStore slowSecretStore(byte[] plaintext, CountDownLatch decryptReturned) {
        return new SecretStore() {
            @Override
            public EncryptedSecret encrypt(byte[] source, SecretContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    decryptReturned.countDown();
                }
                return plaintext;
            }
        };
    }

    private static boolean allZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class TrackingSecretStore implements SecretStore {

        private final List<byte[]> plaintexts = new ArrayList<>();

        @Override
        public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
            byte[] plaintext = PLACEHOLDER_KEY.getBytes(StandardCharsets.US_ASCII);
            plaintexts.add(plaintext);
            return plaintext;
        }

        private boolean allReleased() {
            return plaintexts.stream().allMatch(OpenAiChatCompletionsAdapterTest::allZero);
        }
    }
}
