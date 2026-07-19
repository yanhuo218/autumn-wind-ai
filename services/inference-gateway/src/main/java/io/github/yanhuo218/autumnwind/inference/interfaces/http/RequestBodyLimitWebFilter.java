package io.github.yanhuo218.autumnwind.inference.interfaces.http;

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

    static final int MAX_REQUEST_BYTES = 1_048_576;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(exchange.getRequest().getHeaders().getContentType())) {
            return chain.filter(exchange);
        }
        AtomicLong byteCount = new AtomicLong();
        Flux<DataBuffer> limitedBody = exchange.getRequest().getBody().handle((buffer, sink) -> {
            long total = byteCount.addAndGet(buffer.readableByteCount());
            if (total > MAX_REQUEST_BYTES) {
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
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().getHeaders().set("X-Content-Type-Options", "nosniff");
        return exchange.getResponse().setComplete();
    }

    private static final class RequestBodyTooLargeException extends RuntimeException {
    }
}
