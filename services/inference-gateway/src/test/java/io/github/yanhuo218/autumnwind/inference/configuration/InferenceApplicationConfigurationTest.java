package io.github.yanhuo218.autumnwind.inference.configuration;

import io.github.yanhuo218.autumnwind.inference.application.ChatInferenceService;
import io.github.yanhuo218.autumnwind.inference.chat.OpenAiChatCompletionsAdapter;
import io.github.yanhuo218.autumnwind.inference.credentials.EndpointCredentialResolver;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTargetClient;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeClient;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InferenceApplicationConfigurationTest {

    @Test
    void 生产配置装配唯一Registry客户端和推理应用服务(@TempDir Path directory) throws Exception {
        KeyPair keyPair = generateKeyPair();
        Path masterKey = directory.resolve("master-key-placeholder.txt");
        Files.writeString(masterKey, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
        Path privateKey = writePem(directory, "private-placeholder.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKey = writePem(directory, "public-placeholder.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.secret-store.master-key-file", masterKey.toString());
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.secret-store.key-id", "master-key-placeholder");
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.service-jwt.issuer", "https://inference.internal");
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.service-jwt.private-key-path", privateKey.toString());
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.service-jwt.public-key-path", publicKey.toString());
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.service-jwt.key-id", "inference-key-placeholder");
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.service-jwt.lifetime", "PT30S");
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.model-registry.base-url", "http://127.0.0.1:65535");
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.model-registry.timeout", "PT3S");
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.model-registry.allow-loopback-http-for-test", "true");
            context.getEnvironment().getSystemProperties().put("autumn-wind.inference.http.request-max-bytes", "1048576");
            context.register(InferenceJwtConfiguration.class, InferenceApplicationConfiguration.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(InferenceTargetClient.class).size());
            assertInstanceOf(ChatInferenceService.class, context.getBean(ChatInferenceService.class));
            assertInstanceOf(OpenAiChatCompletionsAdapter.class, context.getBean(OpenAiChatCompletionsAdapter.class));
            assertInstanceOf(EndpointCredentialResolver.class, context.getBean(EndpointCredentialResolver.class));
            assertInstanceOf(SecretStore.class, context.getBean(SecretStore.class));
            assertInstanceOf(PublicAddressPolicy.class, context.getBean(PublicAddressPolicy.class));
            assertInstanceOf(OutboundTargetPolicy.class, context.getBean(OutboundTargetPolicy.class));
            assertInstanceOf(ProviderExchangeClient.class, context.getBean(ProviderExchangeClient.class));
        }
    }

    @Test
    void 严格ObjectMapper拒绝未知字段重复键尾随Token和标量强制转换() throws Exception {
        ObjectMapper mapper = new InferenceApplicationConfiguration().strictObjectMapper();

        assertThrows(JacksonException.class, () -> mapper.readValue("{\"value\":1,\"unknown\":2}", Value.class));
        assertThrows(JacksonException.class, () -> mapper.readValue("{\"value\":1,\"value\":2}", Value.class));
        assertThrows(JacksonException.class, () -> mapper.readValue("{\"value\":1} {}", Value.class));
        assertThrows(JacksonException.class, () -> mapper.readValue("{\"value\":\"1\"}", Value.class));
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static Path writePem(Path directory, String fileName, String type, byte[] encoded) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded);
        Path path = directory.resolve(fileName);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body
                + "\n-----END " + type + "-----\n", StandardCharsets.US_ASCII);
        return path;
    }

    private record Value(int value) {
    }
}
