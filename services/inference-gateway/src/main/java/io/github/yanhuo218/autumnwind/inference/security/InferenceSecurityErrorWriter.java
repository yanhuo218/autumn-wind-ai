package io.github.yanhuo218.autumnwind.inference.security;

import io.github.yanhuo218.autumnwind.inference.interfaces.http.InferenceErrorResponseWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

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
        if (includeBearerChallenge) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return InferenceErrorResponseWriter.write(exchange, status, code, message);
    }
}
