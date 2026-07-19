package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.application.ForbiddenActorException;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import tools.jackson.core.JacksonException;

@RestControllerAdvice
public final class InferenceExceptionHandler {

    private static final String BAD_REQUEST_CODE = "AW-INFERENCE-REQUEST-0001";
    private static final String FORBIDDEN_CODE = "AW-INFERENCE-FORBIDDEN-0001";

    @ExceptionHandler(ForbiddenActorException.class)
    ResponseEntity<ApiErrorResponse> forbidden(ForbiddenActorException ignored, ServerWebExchange exchange) {
        return error(HttpStatus.FORBIDDEN, FORBIDDEN_CODE, "请求中的操作者与认证操作者不一致。", exchange);
    }

    @ExceptionHandler({WebExchangeBindException.class, ServerWebInputException.class,
            DecodingException.class, JacksonException.class, IllegalArgumentException.class})
    ResponseEntity<ApiErrorResponse> badRequest(Exception ignored, ServerWebExchange exchange) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST_CODE, "推理请求格式或字段不合法。", exchange);
    }

    private static ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            ServerWebExchange exchange
    ) {
        String correlationId = CorrelationIdWebFilter.current(exchange);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new ApiErrorResponse(code, message, correlationId));
    }
}
