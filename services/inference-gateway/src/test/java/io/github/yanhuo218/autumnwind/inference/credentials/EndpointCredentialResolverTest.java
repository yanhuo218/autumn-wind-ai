package io.github.yanhuo218.autumnwind.inference.credentials;

import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointCredentialResolverTest {

    private static final UUID OWNER_ID = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
    private static final UUID MODEL_ID = UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001");
    private static final UUID ENDPOINT_ID = UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201");
    private static final UUID CREDENTIAL_ID = UUID.fromString("a24a3063-1e16-49dd-b1a8-6edb9d477810");
    private static final byte[] ZEROES = new byte["PLACEHOLDER_API_KEY_BYTES".length()];

    @Test
    void Mono成功时传入同一数组和固定上下文并在结束后清零() {
        byte[] plaintext = placeholderPlaintext();
        CapturingSecretStore secretStore = new CapturingSecretStore(plaintext);
        EndpointCredentialResolver resolver = new EndpointCredentialResolver(secretStore);
        AtomicReference<byte[]> received = new AtomicReference<>();
        InferenceTarget target = target();

        String result = resolver.withCredential(target, credential -> {
            received.set(credential.apiKey());
            assertFalse(allZero(plaintext));
            return Mono.just("ok");
        }).block();

        assertEquals("ok", result);
        assertSame(plaintext, received.get());
        assertEquals(new SecretContext(OWNER_ID.toString(), "model-endpoint-api-key", ENDPOINT_ID.toString()),
                secretStore.context);
        assertSame(target.credential(), secretStore.encryptedSecret);
        assertArrayEquals(ZEROES, plaintext);
    }

    @Test
    void Mono异常时清零同一数组() {
        byte[] plaintext = placeholderPlaintext();
        EndpointCredentialResolver resolver = new EndpointCredentialResolver(new CapturingSecretStore(plaintext));
        IllegalStateException failure = new IllegalStateException("action-placeholder-failure");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> resolver.withCredential(target(), credential -> Mono.error(failure)).block());

        assertSame(failure, actual);
        assertArrayEquals(ZEROES, plaintext);
    }

    @Test
    void Mono取消时清零同一数组() {
        byte[] plaintext = placeholderPlaintext();
        EndpointCredentialResolver resolver = new EndpointCredentialResolver(new CapturingSecretStore(plaintext));

        Disposable subscription = resolver.withCredential(target(), credential -> Mono.never()).subscribe();
        assertFalse(allZero(plaintext));
        subscription.dispose();

        assertArrayEquals(ZEROES, plaintext);
    }

    @Test
    void action同步抛错时清零同一数组() {
        byte[] plaintext = placeholderPlaintext();
        EndpointCredentialResolver resolver = new EndpointCredentialResolver(new CapturingSecretStore(plaintext));
        IllegalArgumentException failure = new IllegalArgumentException("sync-placeholder-failure");

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                () -> resolver.withCredential(target(), credential -> {
                    throw failure;
                }).block());

        assertSame(failure, actual);
        assertArrayEquals(ZEROES, plaintext);
    }

    @Test
    void Flux取消或断开时清零同一数组() {
        byte[] plaintext = placeholderPlaintext();
        EndpointCredentialResolver resolver = new EndpointCredentialResolver(new CapturingSecretStore(plaintext));

        AtomicReference<String> received = new AtomicReference<>();
        Disposable subscription = resolver.withCredentialFlux(target(),
                        credential -> Flux.concat(Flux.just("first"), Flux.never()))
                .subscribe(received::set);
        assertEquals("first", received.get());
        assertFalse(allZero(plaintext));
        subscription.dispose();

        assertArrayEquals(ZEROES, plaintext);
    }

    @Test
    void 解密失败时不调用action() {
        AtomicBoolean actionCalled = new AtomicBoolean();
        SecretStore secretStore = new SecretStore() {
            @Override
            public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
                throw new IllegalStateException("decrypt-placeholder-failure");
            }
        };
        EndpointCredentialResolver resolver = new EndpointCredentialResolver(secretStore);

        assertThrows(IllegalStateException.class,
                () -> resolver.withCredential(target(), credential -> {
                    actionCalled.set(true);
                    return Mono.just("unused");
                }).block());

        assertFalse(actionCalled.get());
    }

    @Test
    void ResolvedCredential关闭幂等且字符串表示不暴露任何凭据特征() {
        byte[] plaintext = placeholderPlaintext();
        ResolvedCredential credential = new ResolvedCredential(plaintext);

        String value = credential.toString();
        credential.close();
        credential.close();

        assertEquals("ResolvedCredential[apiKey=<REDACTED>]", value);
        assertFalse(value.contains(Integer.toString(plaintext.length)));
        assertArrayEquals(ZEROES, plaintext);
    }

    private static InferenceTarget target() {
        return new InferenceTarget(
                OWNER_ID,
                MODEL_ID,
                "provider-model-placeholder",
                1,
                ENDPOINT_ID,
                URI.create("https://provider.invalid/v1"),
                "OPENAI_COMPATIBLE",
                30,
                2,
                new InferenceTarget.Capabilities("CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT",
                        true, true, false, 8192, 1024),
                CREDENTIAL_ID,
                encryptedSecret()
        );
    }

    private static EncryptedSecret encryptedSecret() {
        return new EncryptedSecret(1, "key-id-placeholder", new byte[12], new byte[48],
                new byte[12], new byte[16]);
    }

    private static byte[] placeholderPlaintext() {
        return "PLACEHOLDER_API_KEY_BYTES".getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean allZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class CapturingSecretStore implements SecretStore {

        private final byte[] plaintext;
        private EncryptedSecret encryptedSecret;
        private SecretContext context;

        private CapturingSecretStore(byte[] plaintext) {
            this.plaintext = plaintext;
        }

        @Override
        public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
            this.encryptedSecret = encryptedSecret;
            this.context = context;
            return plaintext;
        }
    }
}
