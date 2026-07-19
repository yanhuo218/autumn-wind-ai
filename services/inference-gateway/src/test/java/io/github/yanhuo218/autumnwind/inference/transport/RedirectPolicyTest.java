package io.github.yanhuo218.autumnwind.inference.transport;

import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent;
import io.github.yanhuo218.autumnwind.inference.chat.OpenAiChatCompletionsAdapter;
import io.github.yanhuo218.autumnwind.inference.credentials.EndpointCredentialResolver;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.inference.security.TargetPolicyException;
import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import io.github.yanhuo218.autumnwind.security.secrets.SecretContext;
import io.github.yanhuo218.autumnwind.security.secrets.SecretStore;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.netty.tcp.SslProvider;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SNIHostName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectPolicyTest {

    @Test
    void TLS使用原始Host作为SNI和证书主机名校验目标() {
        SslProvider provider = ReactorNettyProviderExchangeClient.sslProviderForHost("provider.invalid");
        SslHandler handler = provider.getSslContext()
                .newHandler(UnpooledByteBufAllocator.DEFAULT, "11.0.0.1", 443);

        try {
            provider.configure(handler);

            assertEquals("HTTPS", handler.engine().getSSLParameters().getEndpointIdentificationAlgorithm());
            assertEquals(List.of(new SNIHostName("provider.invalid")),
                    handler.engine().getSSLParameters().getServerNames());
        } finally {
            handler.engine().closeOutbound();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void 同源重定向逐跳重新校验并在校验后才发起下一次请求(int redirectStatus) throws Exception {
        List<String> events = new ArrayList<>();
        AtomicInteger dnsSequence = new AtomicInteger(1);
        OutboundTargetPolicy policy = new OutboundTargetPolicy(host -> {
            events.add("validate:" + host);
            return List.of(ipv4(11, 0, 0, dnsSequence.getAndIncrement()));
        }, new PublicAddressPolicy());
        ValidatedTarget initial = policy.validate(URI.create("https://provider.invalid/v1/start"));
        Flux<ProviderFrame> redirectBody = Flux.<ProviderFrame>never()
                .doOnCancel(() -> events.add("redirect-body-terminated"));
        ScriptedAttempt attempt = new ScriptedAttempt(events,
                new ResponseSpec(redirectStatus, "/v1/next", redirectBody),
                response(200, null, frame(200, "done")));
        ReactorNettyProviderExchangeClient client = new ReactorNettyProviderExchangeClient(policy, attempt);
        ProviderRequest request = request();

        List<ProviderFrame> frames = client.exchange(initial, request, limits()).collectList().block(Duration.ofSeconds(2));

        assertEquals(1, frames.size());
        assertEquals(200, frames.getFirst().status());
        assertEquals("done", new String(frames.getFirst().data(), StandardCharsets.US_ASCII));

        assertEquals(List.of(
                "validate:provider.invalid",
                "attempt:https://provider.invalid:443/v1/start",
                "redirect-body-terminated",
                "validate:provider.invalid",
                "attempt:https://provider.invalid:443/v1/next"
        ), events);
        assertEquals(2, attempt.targets.size());
        assertEquals("provider.invalid", attempt.targets.get(1).uri().getHost());
        assertEquals(List.of(ipv4(11, 0, 0, 2)), attempt.targets.get(1).addresses());
        assertSame(request, attempt.requests.get(0));
        assertSame(request, attempt.requests.get(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void 重定向到私网时Adapter输出目标拒绝且不重试并清零凭据(int redirectStatus) throws Exception {
        AtomicInteger dnsCount = new AtomicInteger();
        AtomicInteger attemptCount = new AtomicInteger();
        List<byte[]> plaintexts = new ArrayList<>();
        OutboundTargetPolicy policy = new OutboundTargetPolicy(host -> {
            int sequence = dnsCount.incrementAndGet();
            return List.of(sequence % 2 == 1 ? ipv4(11, 0, 0, 1) : ipv4(10, 0, 0, 1));
        }, new PublicAddressPolicy());
        ReactorNettyProviderExchangeClient.HttpAttempt attempt = (target, request, limits, handler) -> {
            attemptCount.incrementAndGet();
            return Flux.from(handler.handle(
                    redirectStatus,
                    "/private-location-placeholder",
                    Flux.never()));
        };
        SecretStore secretStore = new SecretStore() {
            @Override
            public EncryptedSecret encrypt(byte[] plaintext, SecretContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context) {
                byte[] plaintext = "PLACEHOLDER_API_KEY_NOT_FOR_REAL_USE"
                        .getBytes(StandardCharsets.US_ASCII);
                plaintexts.add(plaintext);
                return plaintext;
            }
        };
        OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(
                new ObjectMapper(),
                policy,
                new EndpointCredentialResolver(secretStore),
                new ReactorNettyProviderExchangeClient(policy, attempt));

        List<InferenceEvent> events = adapter.infer(command(), target()).collectList()
                .block(Duration.ofSeconds(2));

        assertEquals(2, events.size());
        assertInstanceOf(InferenceEvent.Start.class, events.getFirst());
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, events.getLast());
        assertEquals(InferenceEvent.ErrorCode.TARGET_REJECTED, error.code());
        assertFalse(error.retryable());
        assertEquals(1, attemptCount.get());
        assertEquals(2, dnsCount.get());
        assertEquals(1, plaintexts.size());
        assertTrue(plaintexts.stream().allMatch(RedirectPolicyTest::allZero));
        assertFalse(events.toString().contains("private-location-placeholder"));
    }

    @Test
    void 重定向后混合公网私网DNS在第二次请求前拒绝() throws Exception {
        AtomicInteger validationCount = new AtomicInteger();
        OutboundTargetPolicy policy = new OutboundTargetPolicy(host -> {
            if (validationCount.incrementAndGet() == 1) {
                return List.of(ipv4(11, 0, 0, 1));
            }
            return List.of(ipv4(11, 0, 0, 2), ipv4(10, 0, 0, 1));
        }, new PublicAddressPolicy());
        ValidatedTarget initial = policy.validate(URI.create("https://provider.invalid/v1/start"));
        ScriptedAttempt attempt = new ScriptedAttempt(new ArrayList<>(), response(307, "/v1/next"));
        ReactorNettyProviderExchangeClient client = new ReactorNettyProviderExchangeClient(policy, attempt);

        TargetPolicyException error = org.junit.jupiter.api.Assertions.assertThrows(
                TargetPolicyException.class,
                () -> client.exchange(initial, request(), limits()).blockLast());

        assertEquals("目标地址不是公网地址。", error.getMessage());
        assertNull(error.getCause());
        assertEquals(2, validationCount.get());
        assertEquals(1, attempt.attemptCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {300, 301, 302, 303, 304, 305, 306, 309})
    void 拒绝307和308之外的所有重定向状态(int status) throws Exception {
        Fixture fixture = fixture(response(status, "/v1/next"));

        assertRejected(fixture.client.exchange(fixture.initial, request(), limits()));

        assertEquals(1, fixture.attempt.attemptCount());
        assertEquals(1, fixture.validationCount.get());
    }

    @Test
    void 跨源重定向在重新发送Authorization之前拒绝() throws Exception {
        AtomicInteger cancellationCount = new AtomicInteger();
        Fixture fixture = fixture(new ResponseSpec(307, "https://other.invalid/v1/next",
                Flux.<ProviderFrame>never().doOnCancel(cancellationCount::incrementAndGet)));

        assertRejected(fixture.client.exchange(fixture.initial, request(), limits()));

        assertEquals(1, fixture.attempt.attemptCount());
        assertEquals(1, fixture.validationCount.get());
        assertEquals(1, cancellationCount.get());
    }

    @Test
    void 拒绝HTTPS降级() throws Exception {
        Fixture fixture = fixture(response(308, "http://provider.invalid/v1/next"));

        assertRejected(fixture.client.exchange(fixture.initial, request(), limits()));

        assertEquals(1, fixture.attempt.attemptCount());
        assertEquals(1, fixture.validationCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "http://["})
    void 拒绝缺失或非法Location(String location) throws Exception {
        Fixture fixture = fixture(response(307, location));

        assertRejected(fixture.client.exchange(fixture.initial, request(), limits()));

        assertEquals(1, fixture.attempt.attemptCount());
        assertEquals(1, fixture.validationCount.get());
    }

    @Test
    void 第四次重定向被拒绝且不会进行第五次请求或DNS校验() throws Exception {
        Fixture fixture = fixture(
                response(307, "/v1/one"),
                response(308, "/v1/two"),
                response(307, "/v1/three"),
                response(308, "/v1/four")
        );

        assertRejected(fixture.client.exchange(fixture.initial, request(), limits()));

        assertEquals(4, fixture.attempt.attemptCount());
        assertEquals(4, fixture.validationCount.get());
    }

    @Test
    void Provider边界字符串表示隐藏Authorization和正文() {
        ProviderRequest request = request();
        ProviderFrame frame = frame(200, "response-placeholder-body");

        assertFalse(request.toString().contains("PLACEHOLDER_API_KEY_BYTES"));
        assertFalse(request.toString().contains("request-placeholder-body"));
        assertFalse(frame.toString().contains("response-placeholder-body"));
        assertTrue(request.toString().contains("<REDACTED>"));
        assertTrue(frame.toString().contains("<REDACTED>"));
    }

    @Test
    void Authorization视图不复制明文且随原数组清零() {
        byte[] apiKey = "PLACEHOLDER_API_KEY_BYTES".getBytes(StandardCharsets.US_ASCII);
        ProviderRequest request = new ProviderRequest(apiKey, new byte[0]);

        CharSequence authorization = request.authorizationHeader();
        assertFalse(authorization instanceof String);
        assertEquals('P', authorization.charAt("Bearer ".length()));

        Arrays.fill(apiKey, (byte) 0);

        assertEquals('\0', authorization.charAt("Bearer ".length()));
        assertEquals("AuthorizationValue[<REDACTED>]", authorization.toString());
    }

    private static Fixture fixture(ResponseSpec... responses) throws Exception {
        AtomicInteger validationCount = new AtomicInteger();
        OutboundTargetPolicy policy = new OutboundTargetPolicy(host -> {
            validationCount.incrementAndGet();
            return List.of(ipv4(11, 0, 0, validationCount.get()));
        }, new PublicAddressPolicy());
        ValidatedTarget initial = policy.validate(URI.create("https://provider.invalid/v1/start"));
        ScriptedAttempt attempt = new ScriptedAttempt(new ArrayList<>(), responses);
        return new Fixture(initial, attempt,
                new ReactorNettyProviderExchangeClient(policy, attempt), validationCount);
    }

    private static void assertRejected(Flux<ProviderFrame> exchange) {
        TargetPolicyException error = org.junit.jupiter.api.Assertions.assertThrows(TargetPolicyException.class,
                exchange::blockLast);
        assertEquals("服务商重定向被拒绝。", error.getMessage());
        assertNull(error.getCause());
        assertEquals(TargetPolicyException.class.getName() + ": 服务商重定向被拒绝。", error.toString());
    }

    private static ChatInferenceCommand command() {
        return new ChatInferenceCommand(
                UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e"),
                UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001"),
                List.of(new ChatInferenceCommand.Message("user", "请求文本占位符")),
                null,
                null,
                null,
                true,
                "correlation-placeholder-redirect");
    }

    private static InferenceTarget target() {
        return new InferenceTarget(
                UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e"),
                UUID.fromString("b88e1f00-83dc-4cf0-a7b3-000000000001"),
                "provider-model-placeholder",
                1,
                UUID.fromString("2d3b1f8a-0ed4-4c3e-a2ab-d1a7580c2201"),
                URI.create("https://provider.invalid/v1"),
                "OPENAI_COMPATIBLE",
                30,
                2,
                new InferenceTarget.Capabilities(
                        "CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT", true, true, true, 8192, 1024),
                UUID.fromString("a24a3063-1e16-49dd-b1a8-6edb9d477810"),
                new EncryptedSecret(1, "key-id-placeholder", new byte[12], new byte[48],
                        new byte[12], new byte[16]));
    }

    private static boolean allZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }

    private static ProviderRequest request() {
        return new ProviderRequest(
                "PLACEHOLDER_API_KEY_BYTES".getBytes(StandardCharsets.US_ASCII),
                "request-placeholder-body".getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static ProviderExchangeLimits limits() {
        return ProviderExchangeLimits.forTargetTimeoutSeconds(30);
    }

    private static ProviderFrame frame(int status, String body) {
        return new ProviderFrame(status, body.getBytes(StandardCharsets.US_ASCII));
    }

    private static ResponseSpec response(int status, String location, ProviderFrame... frames) {
        return new ResponseSpec(status, location, Flux.fromArray(frames));
    }

    private static InetAddress ipv4(int first, int second, int third, int fourth) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{(byte) first, (byte) second, (byte) third, (byte) fourth});
    }

    private record Fixture(
            ValidatedTarget initial,
            ScriptedAttempt attempt,
            ReactorNettyProviderExchangeClient client,
            AtomicInteger validationCount
    ) {
    }

    private record ResponseSpec(int status, String location, Flux<ProviderFrame> body) {
    }

    private static final class ScriptedAttempt implements ReactorNettyProviderExchangeClient.HttpAttempt {

        private final List<String> events;
        private final Deque<ResponseSpec> responses;
        private final List<ValidatedTarget> targets = new ArrayList<>();
        private final List<ProviderRequest> requests = new ArrayList<>();

        private ScriptedAttempt(List<String> events, ResponseSpec... responses) {
            this.events = events;
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Flux<ProviderFrame> exchange(
                ValidatedTarget target,
                ProviderRequest request,
                ProviderExchangeLimits limits,
                ReactorNettyProviderExchangeClient.ResponseHandler handler
        ) {
            events.add("attempt:" + target.uri());
            targets.add(target);
            requests.add(request);
            ResponseSpec response = responses.removeFirst();
            Publisher<ProviderFrame> result = handler.handle(response.status(), response.location(), response.body());
            return Flux.from(result);
        }

        private int attemptCount() {
            return targets.size();
        }
    }
}
