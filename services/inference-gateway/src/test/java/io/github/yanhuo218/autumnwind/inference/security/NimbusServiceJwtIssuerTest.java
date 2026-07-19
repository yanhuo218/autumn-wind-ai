package io.github.yanhuo218.autumnwind.inference.security;

import io.github.yanhuo218.autumnwind.inference.configuration.InferenceServiceJwtProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NimbusServiceJwtIssuerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final UUID ACTOR_USER_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final String KEY_ID = "inference-key-2026";
    private static KeyPair keyPair;
    private static NimbusServiceJwtIssuer issuer;

    @BeforeAll
    static void createIssuer() throws Exception {
        keyPair = generateKeyPair(2048);
        RsaKeyMaterial material = new RsaKeyMaterial(
                (RSAPrivateKey) keyPair.getPrivate(), (RSAPublicKey) keyPair.getPublic(), KEY_ID);
        InferenceServiceJwtProperties properties = new InferenceServiceJwtProperties(
                "https://inference.internal", Path.of("unused-private.pem"), Path.of("unused-public.pem"),
                KEY_ID, Duration.ofSeconds(60));
        issuer = new NimbusServiceJwtIssuer(material, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 签发固定主体且带操作者的单受众RS256令牌() throws Exception {
        String token = issuer.issue(ServiceJwtRequest.actor(
                "model-registry-service", "model-registry.inference.resolve", ACTOR_USER_ID));

        DecodedJwt decoded = decode(token);
        assertEquals("RS256", decoded.header().path("alg").stringValue());
        assertEquals(KEY_ID, decoded.header().path("kid").stringValue());
        assertEquals("https://inference.internal", decoded.claims().path("iss").stringValue());
        assertEquals(InferenceServiceJwtProperties.SUBJECT, decoded.claims().path("sub").stringValue());
        assertSingleAudience(decoded.claims(), "model-registry-service");
        assertEquals(NOW.getEpochSecond(), decoded.claims().path("iat").longValue());
        assertEquals(NOW.plusSeconds(60).getEpochSecond(), decoded.claims().path("exp").longValue());
        assertFalse(decoded.claims().path("jti").stringValue().isBlank());
        assertEquals("model-registry.inference.resolve", decoded.claims().path("scope").stringValue());
        assertEquals(ACTOR_USER_ID.toString(), decoded.claims().path("actor_user_id").stringValue());
        assertValidSignature(decoded);
    }

    @Test
    void 每次签发使用唯一JTI且actor工厂强制操作者() throws Exception {
        ServiceJwtRequest request = ServiceJwtRequest.actor(
                "model-registry-service", "model-registry.inference.resolve", ACTOR_USER_ID);

        String firstJti = decode(issuer.issue(request)).claims().path("jti").stringValue();
        String secondJti = decode(issuer.issue(request)).claims().path("jti").stringValue();

        assertNotEquals(firstJti, secondJti);
        assertEquals(ACTOR_USER_ID, request.actorUserId());
        assertThrows(NullPointerException.class,
                () -> ServiceJwtRequest.actor("model-registry-service", "model-registry.inference.resolve", null));
    }

    @Test
    void 密钥加载拒绝不足2048位或不匹配的公私钥(@TempDir Path directory) throws Exception {
        RsaKeyMaterialLoader loader = new RsaKeyMaterialLoader();
        KeyPair weakPair = generateKeyPair(1024);
        assertThrows(IllegalArgumentException.class, () -> loader.load(
                writePem(directory, "weak-private.pem", "PRIVATE KEY", weakPair.getPrivate().getEncoded()),
                writePem(directory, "weak-public.pem", "PUBLIC KEY", weakPair.getPublic().getEncoded()), KEY_ID));

        KeyPair privatePair = generateKeyPair(2048);
        KeyPair publicPair = generateKeyPair(2048);
        assertThrows(IllegalArgumentException.class, () -> loader.load(
                writePem(directory, "private.pem", "PRIVATE KEY", privatePair.getPrivate().getEncoded()),
                writePem(directory, "public.pem", "PUBLIC KEY", publicPair.getPublic().getEncoded()), KEY_ID));
    }

    private static DecodedJwt decode(String token) throws Exception {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return new DecodedJwt(
                MAPPER.readTree(decoder.decode(parts[0])),
                MAPPER.readTree(decoder.decode(parts[1])),
                parts[0] + "." + parts[1],
                decoder.decode(parts[2]));
    }

    private static void assertValidSignature(DecodedJwt decoded) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(decoded.signingInput().getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(decoded.signature()));
    }

    private static void assertSingleAudience(JsonNode claims, String expectedAudience) {
        JsonNode audience = claims.path("aud");
        if (audience.isArray()) {
            assertEquals(1, audience.size());
            assertEquals(expectedAudience, audience.get(0).stringValue());
            return;
        }
        assertEquals(expectedAudience, audience.stringValue());
    }

    private static Path writePem(Path directory, String fileName, String type, byte[] encoded) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded);
        Path path = directory.resolve(fileName);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body
                + "\n-----END " + type + "-----\n", StandardCharsets.US_ASCII);
        return path;
    }

    private static KeyPair generateKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private record DecodedJwt(JsonNode header, JsonNode claims, String signingInput, byte[] signature) {
    }
}
