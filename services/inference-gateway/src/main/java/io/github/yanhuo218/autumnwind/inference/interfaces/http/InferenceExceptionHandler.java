package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.application.ForbiddenActorException;
import org.springframework.http.HttpStatus;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;

@RestControllerAdvice
public final class InferenceExceptionHandler {

    private static final String BAD_REQUEST_CODE = "AW-INFERENCE-REQUEST-0001";
    private static final String FORBIDDEN_CODE = "AW-INFERENCE-FORBIDDEN-0001";
    private static final String NOT_ACCEPTABLE_CODE = "AW-INFERENCE-NOT_ACCEPTABLE-0001";
    private static final String UNSUPPORTED_MEDIA_TYPE_CODE = "AW-INFERENCE-MEDIA_TYPE-0001";
    private static final String INTERNAL_CODE = "AW-INFERENCE-INTERNAL-0001";

    @ExceptionHandler(ForbiddenActorException.class)
    Mono<Void> forbidden(ForbiddenActorException ignored, ServerWebExchange exchange) {
        return error(HttpStatus.FORBIDDEN, FORBIDDEN_CODE, "请求中的操作者与认证操作者不一致。", exchange);
    }

    @ExceptionHandler({WebExchangeBindException.class, ServerWebInputException.class,
            DecodingException.class, JacksonException.class, IllegalArgumentException.class})
    Mono<Void> badRequest(Exception ignored, ServerWebExchange exchange) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST_CODE, "推理请求格式或字段不合法。", exchange);
    }

    @ExceptionHandler(NotAcceptableStatusException.class)
    Mono<Void> notAcceptable(NotAcceptableStatusException ignored, ServerWebExchange exchange) {
        return error(HttpStatus.NOT_ACCEPTABLE, NOT_ACCEPTABLE_CODE,
                "请求的响应媒体类型不受支持。", exchange);
    }

    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    Mono<Void> unsupportedMediaType(UnsupportedMediaTypeStatusException ignored, ServerWebExchange exchange) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_CODE,
                "请求媒体类型必须为 application/json。", exchange);
    }

    @ExceptionHandler(RequestBodyLimitWebFilter.RequestBodyTooLargeException.class)
    Mono<Void> payloadTooLarge(
            RequestBodyLimitWebFilter.RequestBodyTooLargeException ignored,
            ServerWebExchange exchange
    ) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "AW-INFERENCE-PAYLOAD-0001",
                "推理请求体超过大小限制。", exchange);
    }

    @ExceptionHandler(Exception.class)
    Mono<Void> internal(Exception ignored, ServerWebExchange exchange) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_CODE, "推理服务发生内部错误。", exchange);
    }

    private static Mono<Void> error(
            HttpStatus status,
            String code,
            String message,
            ServerWebExchange exchange
    ) {
        return InferenceErrorResponseWriter.write(exchange, status, code, message);
    }
}
