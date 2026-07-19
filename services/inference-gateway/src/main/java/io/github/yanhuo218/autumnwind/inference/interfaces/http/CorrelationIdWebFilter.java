package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String ATTRIBUTE_NAME = CorrelationIdWebFilter.class.getName() + ".correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{16,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = normalize(exchange.getRequest().getHeaders().getFirst(HEADER_NAME));
        exchange.getAttributes().put(ATTRIBUTE_NAME, correlationId);
        exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    public static String current(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE_NAME);
        if (value instanceof String correlationId && VALID.matcher(correlationId).matches()) {
            return correlationId;
        }
        return UUID.randomUUID().toString();
    }

    static String normalize(String supplied) {
        return supplied != null && VALID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }
}
