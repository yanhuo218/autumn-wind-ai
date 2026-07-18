package io.github.yanhuo218.autumnwind.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver;
import org.springframework.web.server.session.DefaultWebSessionManager;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationIdWebFilterTest {

    private static final String VALID_CORRELATION_ID = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{16,64}");

    @Test
    void 保留合法关联ID并写入交换属性和响应头() {
        AtomicReference<String> valueSeenByChain = new AtomicReference<>();
        ServerWebExchange exchange = exchangeWithHeader(VALID_CORRELATION_ID);

        new CorrelationIdWebFilter().filter(exchange, currentExchange -> {
            valueSeenByChain.set(currentExchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE_NAME));
            return currentExchange.getResponse().setComplete();
        }).block();

        assertEquals(VALID_CORRELATION_ID, valueSeenByChain.get());
        assertEquals(VALID_CORRELATION_ID,
                exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME));
    }

    @Test
    void 非法或缺失关联ID时生成新值并写入响应头() {
        assertGeneratedCorrelationId(exchangeWithHeader("not valid"));
        assertGeneratedCorrelationId(exchangeWithHeader(null));
    }

    private static void assertGeneratedCorrelationId(ServerWebExchange exchange) {
        new CorrelationIdWebFilter().filter(exchange, currentExchange -> currentExchange.getResponse().setComplete()).block();

        String correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE_NAME);
        assertNotNull(correlationId);
        assertTrue(CORRELATION_ID_PATTERN.matcher(correlationId).matches());
        assertFalse("not valid".equals(correlationId));
        assertEquals(correlationId, exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME));
    }

    private static ServerWebExchange exchangeWithHeader(String correlationId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/gateway/test");
        if (correlationId != null) {
            request.header(CorrelationIdWebFilter.HEADER_NAME, correlationId);
        }
        return new DefaultServerWebExchange(request.build(), new MockServerHttpResponse(),
                new DefaultWebSessionManager(), ServerCodecConfigurer.create(),
                new AcceptHeaderLocaleContextResolver());
    }
}
