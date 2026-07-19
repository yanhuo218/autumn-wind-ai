package io.github.yanhuo218.autumnwind.inference.transport;

import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.TargetPolicyException;
import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.ssl.SslContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.NettyPipeline;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

public final class ReactorNettyProviderExchangeClient implements ProviderExchangeClient {

    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int MAX_PROVIDER_FRAME_BYTES = 1_048_576;
    static final long MAX_PROVIDER_RESPONSE_BYTES = 16_777_216L;
    private static final int MAX_REDIRECTS = 3;
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final String REDIRECT_REJECTED_MESSAGE = "服务商重定向被拒绝。";
    private static final String EXCHANGE_FAILED_MESSAGE = "服务商请求失败。";
    private static final SslContext CLIENT_SSL_CONTEXT = clientSslContext();

    private final OutboundTargetPolicy targetPolicy;
    private final HttpAttempt httpAttempt;

    public ReactorNettyProviderExchangeClient(OutboundTargetPolicy targetPolicy) {
        this(targetPolicy, new ReactorNettyHttpAttempt());
    }

    ReactorNettyProviderExchangeClient(OutboundTargetPolicy targetPolicy, HttpAttempt httpAttempt) {
        this.targetPolicy = Objects.requireNonNull(targetPolicy, "出站目标策略不能为空。");
        this.httpAttempt = Objects.requireNonNull(httpAttempt, "HTTP attempt 不能为空。");
    }

    @Override
    public Flux<ProviderFrame> exchange(
            ValidatedTarget target,
            ProviderRequest request,
            ProviderExchangeLimits limits
    ) {
        Objects.requireNonNull(target, "已校验目标不能为空。");
        Objects.requireNonNull(request, "服务商请求不能为空。");
        Objects.requireNonNull(limits, "服务商响应限制不能为空。");
        return exchangeAttempt(target, request, limits, 0)
                .onErrorMap(error -> error instanceof ProviderExchangeException
                        || error instanceof ProviderExchangeClient.ResponseLimitExceededException
                        || error instanceof TargetPolicyException
                        ? error
                        : new ProviderExchangeException(EXCHANGE_FAILED_MESSAGE));
    }

    private Flux<ProviderFrame> exchangeAttempt(
            ValidatedTarget target,
            ProviderRequest request,
            ProviderExchangeLimits limits,
            int redirectCount
    ) {
        return Flux.defer(() -> httpAttempt.exchange(target, request, limits,
                (status, location, body) -> handleResponse(
                        target, request, limits, redirectCount, status, location, limitResponse(body, limits))));
    }

    private Publisher<ProviderFrame> handleResponse(
            ValidatedTarget target,
            ProviderRequest request,
            ProviderExchangeLimits limits,
            int redirectCount,
            int status,
            String location,
            Flux<ProviderFrame> body
    ) {
        if (status < 300 || status > 399) {
            return body;
        }
        if ((status != 307 && status != 308) || redirectCount >= MAX_REDIRECTS) {
            return terminateRedirectBody(body, rejectedRedirect());
        }

        URI redirectUri;
        try {
            redirectUri = resolveLocation(target.uri(), location);
            if (!sameOrigin(target.uri(), redirectUri)) {
                return terminateRedirectBody(body, rejectedRedirect());
            }
        } catch (RuntimeException exception) {
            return terminateRedirectBody(body, rejectedRedirect());
        }
        Flux<ProviderFrame> redirectedExchange = Mono.fromCallable(() -> targetPolicy.validate(redirectUri))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> error instanceof TargetPolicyException
                        ? error
                        : new ProviderExchangeException(EXCHANGE_FAILED_MESSAGE))
                .flatMapMany(redirectedTarget -> sameOrigin(target.uri(), redirectedTarget.uri())
                        ? exchangeAttempt(redirectedTarget, request, limits, redirectCount + 1)
                        : rejectedRedirect());
        return terminateRedirectBody(body, redirectedExchange);
    }

    private static URI resolveLocation(URI current, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException();
        }
        return current.resolve(URI.create(location.trim()));
    }

    private static boolean sameOrigin(URI first, URI second) {
        return "https".equalsIgnoreCase(second.getScheme())
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost() != null
                && second.getHost() != null
                && first.getHost().toLowerCase(Locale.ROOT)
                .equals(second.getHost().toLowerCase(Locale.ROOT))
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? DEFAULT_HTTPS_PORT : uri.getPort();
    }

    private static Flux<ProviderFrame> rejectedRedirect() {
        return Flux.error(new TargetPolicyException(REDIRECT_REJECTED_MESSAGE));
    }

    private static Flux<ProviderFrame> terminateRedirectBody(
            Flux<ProviderFrame> body,
            Flux<ProviderFrame> continuation
    ) {
        return body.takeUntilOther(Mono.just(Boolean.TRUE))
                .thenMany(continuation);
    }

    private static Flux<ProviderFrame> limitResponse(Flux<ProviderFrame> body, ProviderExchangeLimits limits) {
        return Flux.defer(() -> {
            AtomicLong responseBytes = new AtomicLong();
            return body.handle((frame, sink) -> {
                int frameBytes = frame.data().length;
                long total = responseBytes.addAndGet(frameBytes);
                if (frameBytes > limits.maxFrameBytes() || total > limits.maxResponseBytes()) {
                    sink.error(new ProviderExchangeClient.ResponseLimitExceededException());
                    return;
                }
                sink.next(frame);
            });
        });
    }

    static SslProvider sslProviderForHost(String host) {
        return sslProviderForHost(host, CLIENT_SSL_CONTEXT);
    }

    private static SslProvider sslProviderForHost(String host, SslContext sslContext) {
        SslProvider.Builder builder = SslProvider.builder()
                .sslContext(sslContext)
                .handlerConfigurator(handler -> {
                    SSLParameters parameters = handler.engine().getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    handler.engine().setSSLParameters(parameters);
                });
        try {
            builder.serverNames(new SNIHostName(host));
        } catch (IllegalArgumentException ignored) {
            // IP 字面量没有合法 SNI，证书校验仍使用连接的原始 IP host。
        }
        return builder.build();
    }

    private static SslContext clientSslContext() {
        try {
            return Http11SslContextSpec.forClient().sslContext();
        } catch (SSLException exception) {
            throw new IllegalStateException("TLS 客户端配置失败。");
        }
    }

    @FunctionalInterface
    interface HttpAttempt {

        Flux<ProviderFrame> exchange(
                ValidatedTarget target,
                ProviderRequest request,
                ProviderExchangeLimits limits,
                ResponseHandler handler);
    }

    @FunctionalInterface
    interface ResponseHandler {

        Publisher<ProviderFrame> handle(int status, String location, Flux<ProviderFrame> body);
    }

    static final class ReactorNettyHttpAttempt implements HttpAttempt {

        private final SslContext sslContext;

        ReactorNettyHttpAttempt() {
            this(CLIENT_SSL_CONTEXT);
        }

        ReactorNettyHttpAttempt(SslContext sslContext) {
            this.sslContext = Objects.requireNonNull(sslContext, "TLS 客户端上下文不能为空。");
        }

        @Override
        public Flux<ProviderFrame> exchange(
                ValidatedTarget target,
                ProviderRequest request,
                ProviderExchangeLimits limits,
                ResponseHandler handler
        ) {
            return Flux.using(
                    () -> new PinnedAddressResolverGroup(target.addresses()),
                    resolver -> HttpClient.create(ConnectionProvider.newConnection())
                            .followRedirect(false)
                            .disableRetry(true)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                            .responseTimeout(limits.responseHeaderTimeout())
                            .doOnResponse((response, connection) ->
                                    connection.removeHandler(NettyPipeline.ResponseTimeoutHandler))
                            .resolver(resolver)
                            .secure(sslProviderForHost(target.uri().getHost(), sslContext))
                            .headers(headers -> {
                                headers.set(HttpHeaderNames.AUTHORIZATION, request.authorizationHeader());
                                headers.set(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON);
                                headers.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
                            })
                            .post()
                            .uri(target.uri().toString())
                            .send(Mono.fromSupplier(() -> Unpooled.wrappedBuffer(request.body())))
                            .response((response, content) -> {
                                int status = response.status().code();
                                String location = response.responseHeaders().get(HttpHeaderNames.LOCATION);
                                Flux<ProviderFrame> body = content.<ProviderFrame>handle((buffer, reactorSink) -> {
                                            int readableBytes = buffer.readableBytes();
                                            if (readableBytes > limits.maxFrameBytes()) {
                                                reactorSink.error(new ProviderExchangeClient.ResponseLimitExceededException());
                                                return;
                                            }
                                            byte[] data = new byte[readableBytes];
                                            buffer.readBytes(data);
                                            reactorSink.next(new ProviderFrame(status, data));
                                        })
                                        .timeout(limits.streamIdleTimeout())
                                        .switchIfEmpty(Flux.just(new ProviderFrame(status, new byte[0])));
                                return handler.handle(status, location, body);
                            }),
                    PinnedAddressResolverGroup::close
            );
        }
    }

    private static final class ProviderExchangeException extends RuntimeException {

        private ProviderExchangeException(String message) {
            super(message);
        }
    }
}
