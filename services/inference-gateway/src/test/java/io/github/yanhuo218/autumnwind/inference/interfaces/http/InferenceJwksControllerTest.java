package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.configuration.InferenceSecurityConfiguration;
import io.github.yanhuo218.autumnwind.inference.security.InferenceSecurityErrorWriter;
import io.github.yanhuo218.autumnwind.inference.security.RsaKeyMaterial;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceJwksControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_ID = "inference-key-2026";
    private static RSAPublicKey publicKey;
    private static RsaKeyMaterial keyMaterial;
    private AnnotationConfigApplicationContext context;
    private static WebTestClient client;

    @BeforeAll
    static void createKeyMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();
        keyMaterial = new RsaKeyMaterial(
                (RSAPrivateKey) keyPair.getPrivate(), publicKey, KEY_ID);
    }

    @BeforeEach
    void createClient() {
        context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "autumn-wind.inference.conversation-jwt.issuer=https://conversation.internal",
                "autumn-wind.inference.conversation-jwt.audience=inference-gateway",
                "autumn-wind.inference.conversation-jwt.jwk-set-uri=https://conversation.internal/internal/v1/security/jwks",
                "autumn-wind.inference.conversation-jwt.allowed-callers[0]=conversation-service",
                "autumn-wind.inference.conversation-jwt.maximum-lifetime=60s");
        context.register(TestWebConfiguration.class, InferenceSecurityConfiguration.class,
                InferenceSecurityErrorWriter.class, CorrelationIdWebFilter.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context)
                .apply(org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void 固定路径只返回最小公钥JWKS字段并缓存五分钟() throws Exception {
        EntityExchangeResult<byte[]> response = client.get()
                .uri("/internal/v1/security/jwks")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/json")
                .expectHeader().valueEquals("Cache-Control", "public, max-age=300")
                .expectBody()
                .returnResult();

        JsonNode root = MAPPER.readTree(response.getResponseBody());
        assertEquals(1, root.size());
        assertEquals(1, root.path("keys").size());
        JsonNode jwk = root.path("keys").get(0);
        assertEquals(6, jwk.size());
        assertEquals("RSA", jwk.path("kty").stringValue());
        assertEquals(KEY_ID, jwk.path("kid").stringValue());
        assertEquals("sig", jwk.path("use").stringValue());
        assertEquals("RS256", jwk.path("alg").stringValue());
        assertEquals(base64Url(publicKey.getModulus()), jwk.path("n").stringValue());
        assertEquals(base64Url(publicKey.getPublicExponent()), jwk.path("e").stringValue());
        for (String publicField : new String[]{"kty", "kid", "use", "alg", "n", "e"}) {
            assertTrue(jwk.has(publicField));
        }
        for (String privateField : new String[]{"d", "p", "q", "dp", "dq", "qi"}) {
            assertFalse(jwk.has(privateField));
        }
    }

    @Test
    void 匿名GET可访问而POSTPUTDELETE被安全链拒绝() {
        client.get().uri("/internal/v1/security/jwks").exchange()
                .expectStatus().isOk();
        client.post().uri("/internal/v1/security/jwks").exchange()
                .expectStatus().isUnauthorized();
        client.put().uri("/internal/v1/security/jwks").exchange()
                .expectStatus().isUnauthorized();
        client.delete().uri("/internal/v1/security/jwks").exchange()
                .expectStatus().isUnauthorized();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class TestWebConfiguration {

        @Bean
        RsaKeyMaterial inferenceRsaKeyMaterial() {
            return keyMaterial;
        }

        @Bean
        InferenceJwksController inferenceJwksController(RsaKeyMaterial material) {
            return new InferenceJwksController(material);
        }
    }

    private static String base64Url(BigInteger value) {
        byte[] signed = value.toByteArray();
        byte[] unsigned = signed.length > 1 && signed[0] == 0
                ? Arrays.copyOfRange(signed, 1, signed.length)
                : signed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
    }
}
