package io.github.yanhuo218.autumnwind.inference.transport;

import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.netty.tcp.SslProvider;

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
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SNIHostName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        List<ProviderFrame> frames = client.exchange(initial, request).collectList().block(Duration.ofSeconds(2));

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
    @ValueSource(ints = {300, 301, 302, 303, 304, 305, 306, 309})
    void 拒绝307和308之外的所有重定向状态(int status) throws Exception {
        Fixture fixture = fixture(response(status, "/v1/next"));

        assertRejected(fixture.client.exchange(fixture.initial, request()));

        assertEquals(1, fixture.attempt.attemptCount());
        assertEquals(1, fixture.validationCount.get());
    }

    @Test
    void 跨源重定向在重新发送Authorization之前拒绝() throws Exception {
        AtomicInteger cancellationCount = new AtomicInteger();
        Fixture fixture = fixture(new ResponseSpec(307, "https://other.invalid/v1/next",
                Flux.<ProviderFrame>never().doOnCancel(cancellationCount::incrementAndGet)));

        assertRejected(fixture.client.exchange(fixture.initial, request()));

        assertEquals(1, fixture.attempt.attemptCount());
        assertEquals(1, fixture.validationCount.get());
        assertEquals(1, cancellationCount.get());
    }

    @Test
    void 拒绝HTTPS降级() throws Exception {
        Fixture fixture = fixture(response(308, "http://provider.invalid/v1/next"));

        assertRejected(fixture.client.exchange(fixture.initial, request()));

        assertEquals(1, fixture.attempt.attemptCount());
        assertEquals(1, fixture.validationCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "http://["})
    void 拒绝缺失或非法Location(String location) throws Exception {
        Fixture fixture = fixture(response(307, location));

        assertRejected(fixture.client.exchange(fixture.initial, request()));

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

        assertRejected(fixture.client.exchange(fixture.initial, request()));

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
        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                exchange::blockLast);
        assertEquals("服务商重定向被拒绝。", error.getMessage());
    }

    private static ProviderRequest request() {
        return new ProviderRequest(
                "PLACEHOLDER_API_KEY_BYTES".getBytes(StandardCharsets.US_ASCII),
                "request-placeholder-body".getBytes(StandardCharsets.US_ASCII)
        );
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
