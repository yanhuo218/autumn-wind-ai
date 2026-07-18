package io.github.yanhuo218.autumnwind.gateway.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
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

class GatewayJwksControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_ID = "gateway-key-2026";
    private static RSAPublicKey publicKey;
    private static WebTestClient client;

    @BeforeAll
    static void createClient() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();
        RsaKeyMaterial material = new RsaKeyMaterial(
                (RSAPrivateKey) keyPair.getPrivate(), publicKey, KEY_ID);
        client = WebTestClient.bindToController(new GatewayJwksController(material)).build();
    }

    @Test
    void 固定路径只返回公钥JWKS字段并缓存五分钟() throws Exception {
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

    private static String base64Url(BigInteger value) {
        byte[] signed = value.toByteArray();
        byte[] unsigned = signed.length > 1 && signed[0] == 0 ? Arrays.copyOfRange(signed, 1, signed.length) : signed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
    }
}
