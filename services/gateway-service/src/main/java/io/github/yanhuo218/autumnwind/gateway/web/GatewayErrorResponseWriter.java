package io.github.yanhuo218.autumnwind.gateway.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public final class GatewayErrorResponseWriter {

    public Mono<Void> write(ServerWebExchange exchange, GatewayException exception) {
        return write(exchange, statusFor(exception.errorCode()), exception.errorCode(), messageFor(exception.errorCode()));
    }

    public Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatusCode status,
            GatewayErrorCode errorCode,
            String message
    ) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        String correlationId = CorrelationIdWebFilter.current(exchange);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(CorrelationIdWebFilter.HEADER_NAME, correlationId);
        byte[] body = json(errorCode, message, correlationId).getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static HttpStatus statusFor(GatewayErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_SESSION -> HttpStatus.UNAUTHORIZED;
            case ROUTE_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
            case REQUEST_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case IDENTITY_UNAVAILABLE, MODEL_REGISTRY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DOWNSTREAM_PROTOCOL_ERROR -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String messageFor(GatewayErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_SESSION -> "会话无效或已过期。";
            case ROUTE_NOT_ALLOWED -> "当前路由不允许访问。";
            case REQUEST_TOO_LARGE -> "请求正文超过允许的最大长度。";
            case IDENTITY_UNAVAILABLE -> "身份服务暂时不可用。";
            case MODEL_REGISTRY_UNAVAILABLE -> "模型服务暂时不可用。";
            case DOWNSTREAM_PROTOCOL_ERROR -> "下游服务响应不符合网关约束。";
            case INTERNAL_ERROR -> "网关暂时无法处理该请求。";
        };
    }

    private static String json(GatewayErrorCode errorCode, String message, String correlationId) {
        return "{\"code\":\"" + escape(errorCode.value())
                + "\",\"message\":\"" + escape(message)
                + "\",\"correlationId\":\"" + escape(correlationId) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
