package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class InferenceErrorResponseWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private InferenceErrorResponseWriter() {
    }

    public static Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message
    ) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        String correlationId = CorrelationIdWebFilter.current(exchange);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set(CorrelationIdWebFilter.HEADER_NAME, correlationId);
        try {
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(new ApiErrorResponse(code, message, correlationId));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (JacksonException exception) {
            return Mono.error(exception);
        }
    }
}
