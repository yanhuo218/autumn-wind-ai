package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.application.ChatInferenceService;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent;
import io.github.yanhuo218.autumnwind.inference.chat.OpenAiChatCompletionsAdapter;
import io.github.yanhuo218.autumnwind.inference.configuration.InferenceHttpProperties;
import io.github.yanhuo218.autumnwind.inference.credentials.EndpointCredentialResolver;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTargetClient;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver;
import org.springframework.web.server.session.DefaultWebSessionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class InferenceControllerTest {

    private static final String PATH = "/internal/v1/inference/chat-completions";
    private static final UUID OWNER = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final String CORRELATION_ID = "correlation-controller-0001";

    private AnnotationConfigApplicationContext context;
    private WebTestClient client;
    @BeforeEach
    void setUp() {
        configureClient(InferenceHttpProperties.HARD_MAX_BYTES);
    }

    private void configureClient(int requestMaxBytes) {
        if (context != null) {
            context.close();
        }
        TestConfiguration.CALLS.set(0);
        TestConfiguration.REQUEST_MAX_BYTES.set(requestMaxBytes);
        context = new AnnotationConfigApplicationContext();
        context.register(TestConfiguration.class, InferenceController.class,
                InferenceExceptionHandler.class, CorrelationIdWebFilter.class, RequestBodyLimitWebFilter.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void 合法请求以NDJSON顺序流式返回并使用安全响应头() {
        byte[] body = authenticatedPost(validRequest()).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/x-ndjson")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody().returnResult().getResponseBodyContent();
        String raw = new String(body, StandardCharsets.UTF_8);
        assertTrue(raw.endsWith("\n"));
        List<String> lines = raw.lines().toList();

        assertEquals(4, lines.size(), raw);
        assertTrue(lines.get(0).contains("\"type\":\"start\""));
        assertTrue(lines.get(1).contains("\"type\":\"text_delta\""));
        assertTrue(lines.get(2).contains("\"type\":\"usage\""));
        assertTrue(lines.get(3).contains("\"type\":\"done\""));
        assertEquals(1, TestConfiguration.CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[],\"unknown\":true}",
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[]}",
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[{\"role\":\"system\",\"content\":\"placeholder\"}]}",
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}",
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[{\"role\":\"user\",\"content\":\"placeholder\"}],\"temperature\":2.1}",
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[{\"role\":\"user\",\"content\":\"placeholder\"}],\"maxOutputTokens\":131073}",
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[{\"role\":\"user\",\"content\":\"placeholder\"}],\"systemPrompt\":\"\"}",
            "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\",\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\",\"generationId\":\"11111111-1111-4111-8111-111111111111\",\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\",\"messages\":[{\"role\":\"user\",\"content\":\"placeholder\"}]} true"
    })
    void 严格JSON和约束失败返回400且不调用服务(String body) {
        authenticatedPost(body).exchange().expectStatus().isBadRequest();
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 超过256条消息返回400且不调用服务() {
        String messages = "{\"role\":\"user\",\"content\":\"placeholder\"},".repeat(256)
                + "{\"role\":\"user\",\"content\":\"placeholder\"}";
        String body = validRequest().replace("[{\"role\":\"user\",\"content\":\"placeholder\"}]", "[" + messages + "]");

        authenticatedPost(body).exchange().expectStatus().isBadRequest();
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 不匹配的Body操作者返回403且不调用服务() {
        String body = validRequest().replace(OWNER.toString(), "b88e1f00-83dc-4cf0-a7b3-000000000099");
        authenticatedPost(body).exchange().expectStatus().isForbidden();
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 不支持的请求媒体类型返回稳定415错误() {
        client.post().uri(PATH)
                .header("X-Correlation-ID", CORRELATION_ID)
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-INFERENCE-MEDIA_TYPE-0001")
                .jsonPath("$.message").isEqualTo("请求媒体类型必须为 application/json。")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 不支持的响应媒体类型返回稳定406错误() {
        authenticatedPost(validRequest()).accept(MediaType.APPLICATION_XML)
                .exchange()
                .expectStatus().isEqualTo(406)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-INFERENCE-NOT_ACCEPTABLE-0001")
                .jsonPath("$.message").isEqualTo("请求的响应媒体类型不受支持。")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 超过一MiB请求体返回413且不调用服务() {
        String oversized = validRequest() + " ".repeat(1_048_577);
        authenticatedPost(oversized).exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Correlation-ID", CORRELATION_ID)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-INFERENCE-PAYLOAD-0001")
                .jsonPath("$.message").isEqualTo("推理请求体超过大小限制。")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 运行时配置为1024字节时按该上限返回413且不调用服务() {
        configureClient(1024);
        String oversized = validRequest() + " ".repeat(800);

        authenticatedPost(oversized).exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-INFERENCE-PAYLOAD-0001")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 未处理异常返回稳定500且不泄露异常详情() {
        byte[] body = authenticatedPostWithoutActor(validRequest()).exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-INFERENCE-INTERNAL-0001")
                .jsonPath("$.message").isEqualTo("推理服务发生内部错误。")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID)
                .returnResult().getResponseBodyContent();
        assertTrue(!new String(body, StandardCharsets.UTF_8).contains("NullPointerException"));
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 空请求体返回稳定400错误且不调用服务() {
        authenticatedPostWithoutBody().exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Correlation-ID", CORRELATION_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-INFERENCE-REQUEST-0001")
                .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID);
        assertEquals(0, TestConfiguration.CALLS.get());
    }

    @Test
    void 已提交响应的超限请求不会二次写入或泄露异常() {
        MockServerHttpResponse response = new MockServerHttpResponse();
        response.getHeaders().set("X-Existing", "preserved");
        response.setComplete().block();
        ServerWebExchange exchange = new DefaultServerWebExchange(
                MockServerHttpRequest.post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(
                                new byte[InferenceHttpProperties.HARD_MAX_BYTES + 1]))),
                response,
                new DefaultWebSessionManager(),
                org.springframework.http.codec.ServerCodecConfigurer.create(),
                new AcceptHeaderLocaleContextResolver());

        new RequestBodyLimitWebFilter(new InferenceHttpProperties(InferenceHttpProperties.HARD_MAX_BYTES)).filter(exchange,
                current -> current.getRequest().getBody().doOnNext(DataBufferUtils::release).then()).block();

        assertTrue(response.isCommitted());
        assertEquals("preserved", response.getHeaders().getFirst("X-Existing"));
        assertEquals(null, response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals(null, response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    private WebTestClient.RequestHeadersSpec<?> authenticatedPost(String body) {
        return client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("actor_user_id", OWNER.toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .post().uri(PATH)
                .header("X-Correlation-ID", CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("application/x-ndjson"))
                .bodyValue(body);
    }

    private WebTestClient.RequestHeadersSpec<?> authenticatedPostWithoutBody() {
        return client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("actor_user_id", OWNER.toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .post().uri(PATH)
                .header("X-Correlation-ID", CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("application/x-ndjson"));
    }

    private WebTestClient.RequestHeadersSpec<?> authenticatedPostWithoutActor(String body) {
        return client.mutateWith(mockJwt().jwt(jwt -> jwt
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .post().uri(PATH)
                .header("X-Correlation-ID", CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("application/x-ndjson"))
                .bodyValue(body);
    }

    private static String validRequest() {
        return "{\"ownerUserId\":\"f7590cc5-1e56-4a28-ac97-e58380a6d94e\","
                + "\"modelId\":\"b88e1f00-83dc-4cf0-a7b3-000000000001\","
                + "\"generationId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"placeholder\"}]}";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class TestConfiguration {

        private static final AtomicInteger CALLS = new AtomicInteger();
        private static final AtomicInteger REQUEST_MAX_BYTES = new AtomicInteger();

        @Bean
        ChatInferenceService chatInferenceService() {
            InferenceTargetClient targetClient = (owner, model, correlationId) -> {
                CALLS.incrementAndGet();
                return Mono.just(target(owner, model));
            };
            SecretStore secretStore = new SecretStore() {
                @Override
                public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public byte[] decrypt(EncryptedSecret encrypted, SecretContext context) {
                    return "PLACEHOLDER_KEY".getBytes(StandardCharsets.US_ASCII);
                }
            };
            OutboundTargetPolicy policy = new OutboundTargetPolicy(
                    host -> List.of(InetAddress.getByAddress(new byte[]{11, 0, 0, 1})),
                    new PublicAddressPolicy());
            OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(
                    strictMapper(), policy, new EndpointCredentialResolver(secretStore),
                    (target, request, limits) -> Flux.just(
                            new ProviderFrame(200,
                                    "data: {\"choices\":[{\"delta\":{\"content\":\"placeholder\"}}]}\n\n"
                                            .getBytes(StandardCharsets.UTF_8)),
                            new ProviderFrame(200,
                                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n"
                                            .getBytes(StandardCharsets.UTF_8)),
                            new ProviderFrame(200, "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8))));
            return new ChatInferenceService(targetClient, adapter);
        }

        @Bean
        ObjectMapper strictObjectMapper() {
            return strictMapper();
        }

        @Bean
        InferenceHttpProperties inferenceHttpProperties() {
            return new InferenceHttpProperties(REQUEST_MAX_BYTES.get());
        }

        @Bean
        SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(authorize -> authorize.anyExchange().permitAll())
                    .build();
        }

        private static InferenceTarget target(UUID owner, UUID model) {
            return new InferenceTarget(owner, model, "provider-model-placeholder", 1,
                    UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201"),
                    URI.create("https://provider.invalid/v1"), "OPENAI_COMPATIBLE", 30, 1,
                    new InferenceTarget.Capabilities("CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT",
                            true, true, false, 8192, 8192),
                    UUID.fromString("a24a3063-1e16-49dd-b1a8-6edb9d477810"),
                    new EncryptedSecret(1, "key-id-placeholder", new byte[12], new byte[48],
                            new byte[12], new byte[16]));
        }

        private static ObjectMapper strictMapper() {
            return new ObjectMapper().rebuild()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();
        }
    }
}
