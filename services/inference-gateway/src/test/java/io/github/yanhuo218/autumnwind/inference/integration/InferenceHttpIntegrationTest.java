package io.github.yanhuo218.autumnwind.inference.integration;

import io.github.yanhuo218.autumnwind.inference.InferenceGatewayApplication;
import io.github.yanhuo218.autumnwind.inference.security.HostResolver;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeClient;
import io.github.yanhuo218.autumnwind.security.secrets.AesGcmSecretStore;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID OWNER = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID MODEL = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001");
    private static final UUID ENDPOINT = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final String CORRELATION_ID = "correlation-http-integration-0001";
    private static final String CONVERSATION_ISSUER = "https://conversation.invalid";
    private static final String INFERENCE_ISSUER = "https://inference.invalid";
    private static final String API_KEY = "PLACEHOLDER_API_KEY_NOT_FOR_REAL_USE";

    @TempDir
    static Path temporaryDirectory;

    private static ConfigurableApplicationContext application;
    private static FakeModelRegistry fakeRegistry;
    private static FakeOpenAiProvider fakeProvider;
    private static KeyPair conversationKeys;
    private static EncryptedSecret validCredential;
    private static TlsContexts tls;
    private static HttpClient client;
    private static URI inferenceUri;
    private static String previousTrustStore;
    private static String previousTrustStorePassword;
    private static String previousTrustStoreType;
    private static String previousHostsFile;

    @BeforeAll
    static void 启动真实内部推理集成链路() throws Exception {
        tls = createTlsContexts(temporaryDirectory);
        configureJvmTlsAndHosts(tls.keyStorePath());
        conversationKeys = generateRsaKeyPair();
        KeyPair inferenceKeys = generateRsaKeyPair();

        byte[] masterKey = new byte[32];
        new java.security.SecureRandom().nextBytes(masterKey);
        Path masterKeyFile = temporaryDirectory.resolve("master-key-placeholder.txt");
        Files.writeString(masterKeyFile, Base64.getEncoder().encodeToString(masterKey), StandardCharsets.US_ASCII);
        AesGcmSecretStore registryStore = new AesGcmSecretStore(masterKey, "integration-master-key");
        byte[] plaintext = API_KEY.getBytes(StandardCharsets.US_ASCII);
        try {
            validCredential = registryStore.encrypt(plaintext, credentialContext());
        } finally {
            Arrays.fill(masterKey, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }

        fakeProvider = new FakeOpenAiProvider(FakeOpenAiProvider.Scenario.SSE);
        fakeProvider.startHttps(tls.server(), tls.client());
        fakeRegistry = new FakeModelRegistry(
                tls.server(),
                (RSAPublicKey) conversationKeys.getPublic(),
                (RSAPublicKey) inferenceKeys.getPublic(),
                INFERENCE_ISSUER,
                OWNER,
                MODEL,
                fakeProvider.baseUrl(),
                validCredential);
        fakeRegistry.start();

        Path privateKey = writePem(temporaryDirectory, "inference-private-placeholder.pem", "PRIVATE KEY",
                inferenceKeys.getPrivate().getEncoded());
        Path publicKey = writePem(temporaryDirectory, "inference-public-placeholder.pem", "PUBLIC KEY",
                inferenceKeys.getPublic().getEncoded());
        IntegrationConfiguration.PROVIDER = fakeProvider;
        application = new SpringApplicationBuilder(InferenceGatewayApplication.class, IntegrationConfiguration.class)
                .environment(integrationEnvironment(masterKeyFile, privateKey, publicKey))
                .run();
        int port = Integer.parseInt(application.getEnvironment().getProperty("local.server.port"));
        inferenceUri = URI.create("http://127.0.0.1:" + port + "/internal/v1/inference/chat-completions");
        client = HttpClient.newBuilder().sslContext(tls.jdkClient()).connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll
    static void 关闭服务与临时安全材料() {
        if (application != null) {
            application.close();
        }
        if (fakeProvider != null) {
            fakeProvider.closeServer();
        }
        if (fakeRegistry != null) {
            fakeRegistry.close();
        }
        restoreSystemProperty("javax.net.ssl.trustStore", previousTrustStore);
        restoreSystemProperty("javax.net.ssl.trustStorePassword", previousTrustStorePassword);
        restoreSystemProperty("javax.net.ssl.trustStoreType", previousTrustStoreType);
        restoreSystemProperty("jdk.net.hosts.file", previousHostsFile);
    }

    @Test
    void 入站Actor经Registry解析并调用流式Provider后输出NDJSON() throws Exception {
        reset(FakeOpenAiProvider.Scenario.SSE, true, validCredential);

        HttpResponse<String> response = authorizedRequest(validInboundToken(true));

        assertEquals(200, response.statusCode());
        fakeRegistry.assertActor(OWNER);
        fakeRegistry.assertResolutionRequest(OWNER, MODEL);
        fakeProvider.assertPlaceholderAuthorization();
        fakeProvider.assertNoPlatformIdentifiers(OWNER, MODEL);
        assertEquals(List.of("start", "text_delta", "usage", "done"), eventTypes(response.body()));
    }

    @Test
    void 非流式Provider仍输出相同NDJSON事件序列() throws Exception {
        reset(FakeOpenAiProvider.Scenario.NORMAL_NON_STREAM, false, validCredential);

        HttpResponse<String> response = authorizedRequest(validInboundToken(true));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("start", "text_delta", "usage", "done"), eventTypes(response.body()));
        fakeProvider.assertPlaceholderAuthorization();
    }

    @Test
    void Provider在首帧前失败时输出稳定错误事件() throws Exception {
        reset(FakeOpenAiProvider.Scenario.RATE_LIMITED, true, validCredential);

        HttpResponse<String> response = authorizedRequest(validInboundToken(true));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("start", "error"), eventTypes(response.body()));
    }

    @Test
    void Provider在流中返回非法帧时不重试且输出稳定错误事件() throws Exception {
        reset(FakeOpenAiProvider.Scenario.MALFORMED_SSE, true, validCredential);
        HttpResponse<String> response = authorizedRequest(validInboundToken(true));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("start", "error"), eventTypes(response.body()));
    }

    @Test
    void 取消HTTP响应会取消HTTPSProvider流() throws Exception {
        reset(FakeOpenAiProvider.Scenario.SLOW_STREAM, true, validCredential);

        var request = request(validInboundToken(true));
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        fakeProvider.awaitRequest();
        response.cancel(true);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fakeProvider.streamCancelled() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(fakeProvider.streamCancelled());
        assertEventuallyZero(fakeProvider);
    }

    @Test
    void 非法入站Inference令牌不会触达Registry() throws Exception {
        reset(FakeOpenAiProvider.Scenario.SSE, true, validCredential);
        int before = fakeRegistry.resolutionCount();

        HttpResponse<String> response = authorizedRequest(validInboundToken(false));

        assertEquals(401, response.statusCode());
        assertEquals(before, fakeRegistry.resolutionCount());
    }

    @Test
    void Registry返回不匹配AESKeyId时稳定失败且不调用Provider() throws Exception {
        EncryptedSecret mismatch = new EncryptedSecret(
                validCredential.version(),
                "different-integration-master-key",
                validCredential.wrappedDataKeyNonce(),
                validCredential.wrappedDataKey(),
                validCredential.payloadNonce(),
                validCredential.ciphertext());
        reset(FakeOpenAiProvider.Scenario.SSE, true, mismatch);
        int before = fakeProvider.callCount();

        HttpResponse<String> response = authorizedRequest(validInboundToken(true));

        assertEquals(200, response.statusCode());
        assertEquals(List.of("start", "error"), eventTypes(response.body()));
        assertEquals(before, fakeProvider.callCount());
    }

    private static void reset(FakeOpenAiProvider.Scenario scenario, boolean streaming, EncryptedSecret credential) {
        fakeProvider.useScenario(scenario);
        fakeRegistry.useStreaming(streaming);
        fakeRegistry.useCredential(credential);
    }

    private static HttpResponse<String> authorizedRequest(String token) throws Exception {
        return client.send(request(token), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(String token) {
        return HttpRequest.newBuilder(inferenceUri)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", CORRELATION_ID)
                .header("Content-Type", "application/json")
                .header("Accept", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(), StandardCharsets.UTF_8))
                .build();
    }

    private static List<String> eventTypes(String ndjson) throws Exception {
        return ndjson.lines().map(line -> {
            try {
                JsonNode node = MAPPER.readTree(line);
                return node.path("type").stringValue();
            } catch (Exception exception) {
                throw new IllegalStateException("NDJSON 事件格式不合法。", exception);
            }
        }).toList();
    }

    private static void assertEventuallyZero(FakeOpenAiProvider provider) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!allZero(provider.apiKeyReference()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(allZero(provider.apiKeyReference()));
    }

    private static boolean allZero(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String requestBody() {
        return "{\"ownerUserId\":\"" + OWNER + "\",\"modelId\":\"" + MODEL + "\","
                + "\"generationId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"invocationAttemptId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"用户内容占位符\"}]}";
    }

    private static String validInboundToken(boolean expectedAudience) throws Exception {
        Instant now = Instant.now();
        String payload = "{\"iss\":\"" + CONVERSATION_ISSUER + "\",\"sub\":\"conversation-service\","
                + "\"aud\":\"" + (expectedAudience ? "inference-gateway" : "other-service") + "\","
                + "\"scope\":\"inference.chat.invoke\",\"actor_user_id\":\"" + OWNER + "\","
                + "\"jti\":\"" + UUID.randomUUID() + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plusSeconds(30).getEpochSecond() + "}";
        String header = base64Url("{\"alg\":\"RS256\",\"kid\":\"conversation-test-key\"}");
        String body = base64Url(payload);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(conversationKeys.getPrivate());
        signature.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
        return header + "." + body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static Path writePem(Path directory, String fileName, String type, byte[] encoded) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded);
        Path path = directory.resolve(fileName);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n",
                StandardCharsets.US_ASCII);
        return path;
    }

    private static TlsContexts createTlsContexts(Path directory) throws Exception {
        Path keyStorePath = directory.resolve("integration-tls-placeholder.p12");
        String password = UUID.randomUUID().toString();
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "integration-placeholder", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=provider.invalid",
                "-ext", "SAN=dns:provider.invalid,dns:registry.invalid,dns:localhost", "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(), "-storepass", password, "-keypass", password, "-noprompt")
                .redirectErrorStream(true)
                .start();
        try (InputStream output = process.getInputStream()) {
            output.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (process.waitFor() != 0) {
            throw new IllegalStateException("测试 TLS 证书生成失败。");
        }
        char[] passwordCharacters = password.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(keyStorePath)) {
                keyStore.load(input, passwordCharacters);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, passwordCharacters);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            SSLContext jdkClient = SSLContext.getInstance("TLS");
            jdkClient.init(null, trustManagers.getTrustManagers(), null);
            return new TlsContexts(
                    keyStorePath,
                    password,
                    SslContextBuilder.forServer(keyManagers).build(),
                    SslContextBuilder.forClient().trustManager(trustManagers).build(),
                    jdkClient);
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    private static void configureJvmTlsAndHosts(Path keyStorePath) throws Exception {
        previousTrustStore = System.getProperty("javax.net.ssl.trustStore");
        previousTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        previousTrustStoreType = System.getProperty("javax.net.ssl.trustStoreType");
        previousHostsFile = System.getProperty("jdk.net.hosts.file");
        Path hosts = temporaryDirectory.resolve("hosts-placeholder.txt");
        Files.writeString(hosts, "127.0.0.1 registry.invalid provider.invalid\n", StandardCharsets.US_ASCII);
        System.setProperty("javax.net.ssl.trustStore", keyStorePath.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", tlsPassword());
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("jdk.net.hosts.file", hosts.toString());
    }

    private static StandardEnvironment integrationEnvironment(
            Path masterKeyFile,
            Path privateKey,
            Path publicKey
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("server.port", "0");
        values.put("spring.main.allow-bean-definition-overriding", "true");
        values.put("autumn-wind.inference.http.request-max-bytes", "1048576");
        values.put("autumn-wind.inference.secret-store.master-key-file", masterKeyFile.toString());
        values.put("autumn-wind.inference.secret-store.key-id", "integration-master-key");
        values.put("autumn-wind.inference.service-jwt.issuer", INFERENCE_ISSUER);
        values.put("autumn-wind.inference.service-jwt.private-key-path", privateKey.toString());
        values.put("autumn-wind.inference.service-jwt.public-key-path", publicKey.toString());
        values.put("autumn-wind.inference.service-jwt.key-id", "inference-test-key");
        values.put("autumn-wind.inference.service-jwt.lifetime", "PT30S");
        values.put("autumn-wind.inference.model-registry.base-url", fakeRegistry.baseUrl());
        values.put("autumn-wind.inference.model-registry.timeout", "PT5S");
        values.put("autumn-wind.inference.model-registry.allow-loopback-http-for-test", "false");
        values.put("autumn-wind.inference.conversation-jwt.issuer", CONVERSATION_ISSUER);
        values.put("autumn-wind.inference.conversation-jwt.audience", "inference-gateway");
        values.put("autumn-wind.inference.conversation-jwt.jwk-set-uri", fakeRegistry.jwksUrl());
        values.put("autumn-wind.inference.conversation-jwt.allowed-callers", "conversation-service");
        values.put("autumn-wind.inference.conversation-jwt.maximum-lifetime", "PT60S");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("integration-test", values));
        return environment;
    }

    private static String tlsPassword() {
        return tls.password();
    }

    private static SecretContext credentialContext() {
        return new SecretContext(OWNER.toString(), "model-endpoint-api-key", ENDPOINT.toString());
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationConfiguration {

        private static ProviderExchangeClient PROVIDER;

        @Bean("jdkHostResolver")
        HostResolver jdkHostResolver() {
            return host -> List.of(InetAddress.getByAddress(new byte[]{11, 0, 0, 1}));
        }

        @Bean("providerExchangeClient")
        ProviderExchangeClient providerExchangeClient() {
            return PROVIDER;
        }
    }

    private record TlsContexts(
            Path keyStorePath,
            String password,
            SslContext server,
            SslContext client,
            SSLContext jdkClient
    ) {
    }
}
