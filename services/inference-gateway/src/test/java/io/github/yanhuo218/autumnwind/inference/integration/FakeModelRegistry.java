package io.github.yanhuo218.autumnwind.inference.integration;

import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslContext;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仅供集成测试使用的受控 Registry 服务：验证 Gateway 服务令牌并返回加密凭据信封。
 */
final class FakeModelRegistry implements AutoCloseable {

    private static final String RESOLUTION_PATH = "/internal/v1/model-registry/inference-target-resolutions";
    private static final String JWKS_PATH = "/internal/v1/security/jwks";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SslContext tlsContext;
    private final RSAPublicKey conversationPublicKey;
    private final RSAPublicKey inferencePublicKey;
    private final String inferenceIssuer;
    private final UUID expectedOwner;
    private final UUID expectedModel;
    private final String providerBaseUrl;
    private final AtomicInteger resolutionCount = new AtomicInteger();
    private final AtomicReference<UUID> resolvedActor = new AtomicReference<>();
    private final AtomicReference<UUID> resolvedOwner = new AtomicReference<>();
    private final AtomicReference<UUID> resolvedModel = new AtomicReference<>();
    private final AtomicReference<String> validationFailure = new AtomicReference<>();

    private volatile EncryptedSecret credential;
    private volatile boolean streaming = true;
    private DisposableServer server;

    FakeModelRegistry(
            SslContext tlsContext,
            RSAPublicKey conversationPublicKey,
            RSAPublicKey inferencePublicKey,
            String inferenceIssuer,
            UUID expectedOwner,
            UUID expectedModel,
            String providerBaseUrl,
            EncryptedSecret credential
    ) {
        this.tlsContext = Objects.requireNonNull(tlsContext, "TLS 上下文不能为空。");
        this.conversationPublicKey = Objects.requireNonNull(conversationPublicKey, "Conversation 公钥不能为空。");
        this.inferencePublicKey = Objects.requireNonNull(inferencePublicKey, "Inference 公钥不能为空。");
        this.inferenceIssuer = Objects.requireNonNull(inferenceIssuer, "Inference issuer 不能为空。");
        this.expectedOwner = Objects.requireNonNull(expectedOwner, "预期操作者不能为空。");
        this.expectedModel = Objects.requireNonNull(expectedModel, "预期模型不能为空。");
        this.providerBaseUrl = Objects.requireNonNull(providerBaseUrl, "Provider 地址不能为空。");
        this.credential = Objects.requireNonNull(credential, "凭据不能为空。");
    }

    void start() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .secure(spec -> spec.sslContext(tlsContext))
                .handle((request, response) -> {
                    if (JWKS_PATH.equals(request.uri())) {
                        return response.header(HttpHeaderNames.CACHE_CONTROL, "no-store")
                                .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                .sendString(Mono.just(conversationJwks()));
                    }
                    if (!RESOLUTION_PATH.equals(request.uri())) {
                        return response.status(404).send();
                    }
                    resolutionCount.incrementAndGet();
                    if (!"POST".equals(request.method().name())) {
                        return response.status(405).header(HttpHeaderNames.CACHE_CONTROL, "no-store").send();
                    }
                    String error = validateServiceToken(request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION));
                    if (error != null) {
                        validationFailure.set(error);
                        return response.status(401).header(HttpHeaderNames.CACHE_CONTROL, "no-store").send();
                    }
                    return request.receive().aggregate().asByteArray().flatMap(bytes -> {
                        int status = validateResolutionRequest(bytes, resolvedActor.get());
                        if (status != 0) {
                            return response.status(status).header(HttpHeaderNames.CACHE_CONTROL, "no-store").send().then();
                        }
                        return response.header(HttpHeaderNames.CACHE_CONTROL, "no-store")
                                .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                .sendString(Mono.just(resolutionResponse()))
                                .then();
                    });
                })
                .bindNow();
    }

    String baseUrl() {
        requireStarted();
        return "https://localhost:" + server.port();
    }

    String jwksUrl() {
        return baseUrl() + JWKS_PATH;
    }

    int resolutionCount() {
        return resolutionCount.get();
    }

    void useCredential(EncryptedSecret value) {
        credential = Objects.requireNonNull(value, "凭据不能为空。");
    }

    void useStreaming(boolean value) {
        streaming = value;
    }

    void assertActor(UUID actor) {
        assertNotNull(resolvedActor.get(), "Registry 未收到有效的服务令牌。");
        assertEquals(actor, resolvedActor.get());
        assertEquals(null, validationFailure.get());
    }

    void assertResolutionRequest(UUID owner, UUID model) {
        assertEquals(owner, resolvedOwner.get());
        assertEquals(model, resolvedModel.get());
        assertEquals(null, validationFailure.get());
    }

    @Override
    public void close() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
    }

    private String validateServiceToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return "缺少服务令牌。";
        }
        try {
            String token = authorization.substring("Bearer ".length());
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !validSignature(parts, inferencePublicKey)) {
                return "服务令牌签名无效。";
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonNode header = MAPPER.readTree(decoder.decode(parts[0]));
            JsonNode claims = MAPPER.readTree(decoder.decode(parts[1]));
            if (!"RS256".equals(header.path("alg").stringValue())) {
                return "服务令牌算法无效。";
            }
            if (!inferenceIssuer.equals(claims.path("iss").stringValue())
                    || !"inference-gateway-service".equals(claims.path("sub").stringValue())
                    || !hasAudience(claims.path("aud"), "model-registry-service")
                    || !"model-registry.inference.resolve".equals(claims.path("scope").stringValue())
                    || claims.path("jti").stringValue().isBlank()) {
                return "服务令牌声明无效。";
            }
            UUID actor = UUID.fromString(claims.path("actor_user_id").stringValue());
            if (!expectedOwner.equals(actor) || !hasSafeLifetime(claims)) {
                return "服务令牌操作者或有效期无效。";
            }
            resolvedActor.set(actor);
            return null;
        } catch (Exception ignored) {
            return "服务令牌格式无效。";
        }
    }

    private int validateResolutionRequest(byte[] bytes, UUID actor) {
        try {
            JsonNode root = MAPPER.readTree(bytes);
            if (root == null || !root.isObject()) {
                return 400;
            }
            UUID owner = canonicalUuid(root.path("ownerUserId").stringValue());
            UUID model = canonicalUuid(root.path("modelId").stringValue());
            if (owner == null || model == null) {
                return 400;
            }
            if (!owner.equals(actor) || !owner.equals(expectedOwner) || !model.equals(expectedModel)) {
                return 403;
            }
            resolvedOwner.set(owner);
            resolvedModel.set(model);
            return 0;
        } catch (RuntimeException exception) {
            return 400;
        }
    }

    private static UUID canonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equals(value) ? uuid : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String conversationJwks() {
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"conversation-test-key\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + base64Url(conversationPublicKey.getModulus()) + "\",\"e\":\""
                + base64Url(conversationPublicKey.getPublicExponent()) + "\"}]}";
    }

    private String resolutionResponse() {
        EncryptedSecret current = credential;
        return "{\"modelId\":\"" + expectedModel + "\",\"providerModelId\":\"provider-model-placeholder\","
                + "\"modelVersion\":7,\"endpointId\":\"2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201\","
                + "\"endpointBaseUrl\":\"" + providerBaseUrl + "\",\"endpointProtocol\":\"OPENAI_COMPATIBLE\","
                + "\"endpointRequestTimeoutSeconds\":5,\"endpointVersion\":11,\"capabilities\":{"
                + "\"interfaceType\":\"CHAT_COMPLETIONS\",\"inputModalities\":[\"TEXT\"],\"outputModality\":\"TEXT\","
                + "\"streaming\":" + streaming + ",\"systemPrompt\":true,\"reasoning\":false,\"contextLength\":8192,\"maxOutputLength\":1024},"
                + "\"credentialId\":\"a24a3063-1e16-49dd-b1a8-6edb9d477810\",\"credential\":{"
                + "\"version\":" + current.version() + ",\"keyId\":\"" + current.keyId() + "\","
                + "\"wrappedDataKeyNonce\":\"" + base64(current.wrappedDataKeyNonce()) + "\","
                + "\"wrappedDataKey\":\"" + base64(current.wrappedDataKey()) + "\","
                + "\"payloadNonce\":\"" + base64(current.payloadNonce()) + "\","
                + "\"ciphertext\":\"" + base64(current.ciphertext()) + "\"}}";
    }

    private static boolean validSignature(String[] parts, PublicKey key) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    private static boolean hasAudience(JsonNode audience, String expected) {
        if (audience.isArray()) {
            return audience.size() == 1 && expected.equals(audience.get(0).stringValue());
        }
        return expected.equals(audience.stringValue());
    }

    private static boolean hasSafeLifetime(JsonNode claims) {
        long issuedAt = claims.path("iat").longValue();
        long expiresAt = claims.path("exp").longValue();
        long now = Instant.now().getEpochSecond();
        return issuedAt > 0 && expiresAt > issuedAt && expiresAt - issuedAt <= 60
                && issuedAt <= now + 60 && expiresAt >= now - 60;
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static String base64Url(BigInteger value) {
        byte[] signed = value.toByteArray();
        byte[] unsigned = signed.length > 1 && signed[0] == 0
                ? Arrays.copyOfRange(signed, 1, signed.length) : signed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
    }

    private void requireStarted() {
        if (server == null) {
            throw new IllegalStateException("测试服务尚未启动。");
        }
    }
}
