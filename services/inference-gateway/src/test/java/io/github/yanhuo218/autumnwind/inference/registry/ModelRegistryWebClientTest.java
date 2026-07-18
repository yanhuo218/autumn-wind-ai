package io.github.yanhuo218.autumnwind.inference.registry;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRegistryWebClientTest {

    private static final UUID OWNER_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID MODEL_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final UUID CREDENTIAL_ID = UUID.fromString("a24a3063-1e16-49dd-b1a8-6edb9d477810");
    private static final String CORRELATION_ID = "correlation-placeholder-0001";
    private static final String SERVICE_JWT = "service-jwt-placeholder";
    private static final URI REGISTRY_BASE_URI = URI.create("https://registry.invalid");
    private static final URI ENDPOINT_BASE_URI = URI.create("https://provider.invalid/v1");

    @Test
    void 按租户取得JWT并发送精确请求后映射全部快照字段() throws Exception {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<UUID> tokenOwner = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            capturedRequest.set(request);
            capturedBody.set(body(request));
            return Mono.just(successResponse());
        };
        WebClient webClient = WebClient.builder()
                .baseUrl(REGISTRY_BASE_URI.toString())
                .exchangeFunction(exchange)
                .build();
        ModelRegistryWebClient client = new ModelRegistryWebClient(webClient, ownerUserId -> {
            tokenOwner.set(ownerUserId);
            return Mono.just(SERVICE_JWT);
        });

        InferenceTarget target = client.resolve(OWNER_ID, MODEL_ID, CORRELATION_ID).block();

        ClientRequest request = capturedRequest.get();
        assertEquals(OWNER_ID, tokenOwner.get());
        assertEquals(HttpMethod.POST, request.method());
        assertEquals(URI.create("https://registry.invalid/internal/v1/model-registry/inference-target-resolutions"),
                request.url());
        assertEquals("Bearer " + SERVICE_JWT, request.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals(CORRELATION_ID, request.headers().getFirst("X-Correlation-ID"));
        assertEquals(MediaType.APPLICATION_JSON, request.headers().getContentType());
        assertEquals(Set.of(MediaType.APPLICATION_JSON), Set.copyOf(request.headers().getAccept()));

        assertEquals("{\"ownerUserId\":\"" + OWNER_ID + "\",\"modelId\":\"" + MODEL_ID + "\"}",
                capturedBody.get());

        assertEquals(OWNER_ID, target.ownerUserId());
        assertEquals(MODEL_ID, target.modelId());
        assertEquals("provider-model-placeholder", target.providerModelId());
        assertEquals(7, target.modelVersion());
        assertEquals(ENDPOINT_ID, target.endpointId());
        assertEquals(ENDPOINT_BASE_URI, target.endpointBaseUrl());
        assertEquals("OPENAI_COMPATIBLE", target.endpointProtocol());
        assertEquals(45, target.endpointRequestTimeoutSeconds());
        assertEquals(11, target.endpointVersion());
        assertEquals("CHAT_COMPLETIONS", target.capabilities().interfaceType());
        assertEquals(Set.of("TEXT", "IMAGE"), target.capabilities().inputModalities());
        assertEquals("TEXT", target.capabilities().outputModality());
        assertTrue(target.capabilities().streaming());
        assertTrue(target.capabilities().systemPrompt());
        assertFalse(target.capabilities().reasoning());
        assertEquals(8192, target.capabilities().contextLength());
        assertEquals(1024, target.capabilities().maxOutputLength());
        assertEquals(CREDENTIAL_ID, target.credentialId());
        assertEncryptedEnvelope(target.credential());
    }

    @Test
    void 非成功响应转换为不泄露响应和请求信息的稳定异常() {
        String responseBody = "registry-private-detail-placeholder";
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.CONFLICT)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        WebClient webClient = WebClient.builder()
                .baseUrl(REGISTRY_BASE_URI.toString())
                .exchangeFunction(exchange)
                .build();
        ModelRegistryWebClient client = new ModelRegistryWebClient(webClient,
                ownerUserId -> Mono.just(SERVICE_JWT));

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> client.resolve(OWNER_ID, MODEL_ID, CORRELATION_ID).block());

        assertEquals("模型 Registry 请求失败。", error.getMessage());
        assertFalse(error.toString().contains(responseBody));
        assertFalse(error.toString().contains(REGISTRY_BASE_URI.toString()));
        assertFalse(error.toString().contains(SERVICE_JWT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-store", "NO-STORE", "private, no-store"})
    void 接受独立NoStore缓存指令(String cacheControl) {
        ModelRegistryWebClient client = client(responseWithCacheControl(cacheControl));

        InferenceTarget target = client.resolve(OWNER_ID, MODEL_ID, CORRELATION_ID).block();

        assertEquals(MODEL_ID, target.modelId());
    }

    @Test
    void 接受多个CacheControl头中的独立NoStore指令() {
        ModelRegistryWebClient client = client(responseWithCacheControl("private", "no-store"));

        InferenceTarget target = client.resolve(OWNER_ID, MODEL_ID, CORRELATION_ID).block();

        assertEquals(MODEL_ID, target.modelId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-no-store", "no-storex", "no-store=value"})
    void 拒绝包含NoStore字样但不是独立指令的值(String cacheControl) {
        ModelRegistryWebClient client = client(responseWithCacheControl(cacheControl));

        assertStableFailure(client);
    }

    @Test
    void 成功响应空正文转换为不泄露请求信息的稳定异常() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
        ModelRegistryWebClient client = client(response);

        RuntimeException error = assertStableFailure(client);

        assertFalse(error.toString().contains(REGISTRY_BASE_URI.toString()));
        assertFalse(error.toString().contains(SERVICE_JWT));
    }

    @Test
    void 快照字符串表示隐藏目标地址和加密信封() {
        ModelRegistryWebClient client = new ModelRegistryWebClient(
                WebClient.builder().baseUrl(REGISTRY_BASE_URI.toString())
                        .exchangeFunction(request -> Mono.just(successResponse()))
                        .build(),
                ownerUserId -> Mono.just(SERVICE_JWT));

        String value = client.resolve(OWNER_ID, MODEL_ID, CORRELATION_ID).block().toString();

        assertFalse(value.contains(ENDPOINT_BASE_URI.toString()));
        assertFalse(value.contains("key-id-placeholder"));
        assertFalse(value.contains(base64(16)));
        assertFalse(value.contains(SERVICE_JWT));
    }

    private static String body(ClientRequest request) {
        MockClientHttpRequest output = new MockClientHttpRequest(request.method(), request.url());
        request.body().insert(output, new BodyInserter.Context() {
            @Override
            public java.util.List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return output.getBodyAsString().block();
    }

    private static ClientResponse successResponse() {
        return responseWithCacheControl("no-store");
    }

    private static ClientResponse responseWithCacheControl(String... cacheControl) {
        String json = """
                {
                  "modelId":"%s",
                  "providerModelId":"provider-model-placeholder",
                  "modelVersion":7,
                  "endpointId":"%s",
                  "endpointBaseUrl":"%s",
                  "endpointProtocol":"OPENAI_COMPATIBLE",
                  "endpointRequestTimeoutSeconds":45,
                  "endpointVersion":11,
                  "capabilities":{
                    "interfaceType":"CHAT_COMPLETIONS",
                    "inputModalities":["TEXT","IMAGE"],
                    "outputModality":"TEXT",
                    "streaming":true,
                    "systemPrompt":true,
                    "reasoning":false,
                    "contextLength":8192,
                    "maxOutputLength":1024
                  },
                  "credentialId":"%s",
                  "credential":{
                    "version":1,
                    "keyId":"key-id-placeholder",
                    "wrappedDataKeyNonce":"%s",
                    "wrappedDataKey":"%s",
                    "payloadNonce":"%s",
                    "ciphertext":"%s"
                  }
                }
                """.formatted(MODEL_ID, ENDPOINT_ID, ENDPOINT_BASE_URI, CREDENTIAL_ID,
                base64(12), base64(48), base64(12), base64(16));
        ClientResponse.Builder response = ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .body(json);
        return response.build();
    }

    private static ModelRegistryWebClient client(ClientResponse response) {
        return new ModelRegistryWebClient(
                WebClient.builder().baseUrl(REGISTRY_BASE_URI.toString())
                        .exchangeFunction(request -> Mono.just(response))
                        .build(),
                ownerUserId -> Mono.just(SERVICE_JWT));
    }

    private static RuntimeException assertStableFailure(ModelRegistryWebClient client) {
        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> client.resolve(OWNER_ID, MODEL_ID, CORRELATION_ID).block());
        assertEquals("模型 Registry 请求失败。", error.getMessage());
        return error;
    }

    private static void assertEncryptedEnvelope(EncryptedSecret secret) {
        assertEquals(1, secret.version());
        assertEquals("key-id-placeholder", secret.keyId());
        assertEquals(12, secret.wrappedDataKeyNonce().length);
        assertEquals(48, secret.wrappedDataKey().length);
        assertEquals(12, secret.payloadNonce().length);
        assertEquals(16, secret.ciphertext().length);
    }

    private static String base64(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index + 1);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
