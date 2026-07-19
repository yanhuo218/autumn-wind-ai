package io.github.yanhuo218.autumnwind.inference.security;

import io.github.yanhuo218.autumnwind.inference.interfaces.http.ApiErrorResponse;
import io.github.yanhuo218.autumnwind.inference.interfaces.http.CorrelationIdWebFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public final class InferenceSecurityErrorWriter {

    private static final String UNAUTHORIZED_CODE = "AW-INFERENCE-AUTH-0001";
    private static final String FORBIDDEN_CODE = "AW-INFERENCE-FORBIDDEN-0001";

    public Mono<Void> writeUnauthorized(ServerWebExchange exchange, Exception ignored) {
        return write(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_CODE, "Conversation JWT 无效或缺失。", true);
    }

    public Mono<Void> writeForbidden(ServerWebExchange exchange, Exception ignored) {
        return write(exchange, HttpStatus.FORBIDDEN, FORBIDDEN_CODE, "当前调用方无权执行推理请求。", false);
    }

    private static Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message,
            boolean includeBearerChallenge
    ) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        String correlationId = CorrelationIdWebFilter.current(exchange);
        ApiErrorResponse error = new ApiErrorResponse(code, message, correlationId);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set(CorrelationIdWebFilter.HEADER_NAME, correlationId);
        if (includeBearerChallenge) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        byte[] body = json(error).getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static String json(ApiErrorResponse error) {
        return "{\"code\":\"" + error.code() + "\",\"message\":\"" + error.message()
                + "\",\"correlationId\":\"" + error.correlationId() + "\"}";
    }
}
