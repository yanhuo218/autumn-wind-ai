package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryApplicationException;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class ModelRegistryExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelRegistryExceptionHandler.class);

    @ExceptionHandler(ModelRegistryApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(ModelRegistryApplicationException exception,
                                                                         HttpServletRequest request) {
        return response(statusFor(exception.code()), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ModelRegistryErrorCode.INVALID_REQUEST,
                "请求字段不合法。", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleArgumentTypeMismatch(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ModelRegistryErrorCode.INVALID_REQUEST,
                "请求路径参数不合法。", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ModelRegistryErrorCode.INVALID_REQUEST,
                "请求媒体类型不受支持。", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpServletRequest request) {
        return response(HttpStatus.NOT_ACCEPTABLE, ModelRegistryErrorCode.INVALID_REQUEST,
                "响应媒体类型不受支持。", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, ModelRegistryErrorCode.ACCESS_DENIED,
                "当前服务无权执行该操作。", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Model Registry 接口发生未处理异常，correlationId={}，exceptionType={}",
                CorrelationIdFilter.current(request), exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ModelRegistryErrorCode.INTERNAL_ERROR,
                "服务器暂时无法处理请求。", request);
    }

    private static HttpStatus statusFor(ModelRegistryErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INVALID_SERVICE_TOKEN -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case ENDPOINT_NOT_FOUND, MODEL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ENDPOINT_VERSION_CONFLICT, ENDPOINT_TEST_UNAVAILABLE, MODEL_CONFLICT, DEFAULT_MODEL_CONFLICT,
                    MODEL_VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case ENDPOINT_SECRET_FAILURE, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ResponseEntity<ApiErrorResponse> response(HttpStatus status, ModelRegistryErrorCode code,
                                                              String message, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code.value(), message, correlationId, List.of()));
    }
}
