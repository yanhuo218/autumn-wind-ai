package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.configuration.InferenceHttpProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestBodyLimitWebFilter implements WebFilter {

    private final int maxRequestBytes;

    public RequestBodyLimitWebFilter(InferenceHttpProperties properties) {
        this.maxRequestBytes = properties.requestMaxBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(exchange.getRequest().getHeaders().getContentType())) {
            return chain.filter(exchange);
        }
        AtomicLong byteCount = new AtomicLong();
        Flux<DataBuffer> limitedBody = exchange.getRequest().getBody().handle((buffer, sink) -> {
            long total = byteCount.addAndGet(buffer.readableByteCount());
            if (total > maxRequestBytes) {
                DataBufferUtils.release(buffer);
                sink.error(new RequestBodyTooLargeException());
                return;
            }
            sink.next(buffer);
        });
        ServerHttpRequestDecorator request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return limitedBody;
            }
        };
        return chain.filter(exchange.mutate().request(request).build())
                .onErrorResume(RequestBodyTooLargeException.class, ignored -> writePayloadTooLarge(exchange));
    }

    private static Mono<Void> writePayloadTooLarge(ServerWebExchange exchange) {
        return InferenceErrorResponseWriter.write(
                exchange,
                HttpStatus.PAYLOAD_TOO_LARGE,
                "AW-INFERENCE-PAYLOAD-0001",
                "推理请求体超过大小限制。");
    }

    static final class RequestBodyTooLargeException extends RuntimeException {
    }
}
