package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class IdentityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityExceptionHandler.class);

    @ExceptionHandler(IdentityApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(
            IdentityApplicationException exception,
            HttpServletRequest request
    ) {
        return response(statusFor(exception.errorCode()), exception.errorCode(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorView> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorView(error.getField(), "字段值不合法。"))
                .distinct()
                .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                IdentityErrorCode.INVALID_REQUEST,
                "请求字段不合法。",
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                IdentityErrorCode.INVALID_REQUEST,
                "请求正文格式不正确。",
                request,
                List.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                "Identity 接口发生未处理异常，correlationId={}，exceptionType={}",
                CorrelationIdFilter.current(request),
                exception.getClass().getName()
        );
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                IdentityErrorCode.INTERNAL_ERROR,
                "服务器暂时无法处理请求。",
                request,
                List.of()
        );
    }

    private static HttpStatus statusFor(IdentityErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case REGISTRATION_NOT_ALLOWED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case AUTHENTICATION_FAILED, INVALID_SESSION, INVALID_SERVICE_TOKEN -> HttpStatus.UNAUTHORIZED;
            case REGISTRATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case POLICY_UNAVAILABLE, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            IdentityErrorCode errorCode,
            String message,
            HttpServletRequest request,
            List<FieldErrorView> fieldErrors
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                errorCode.value(),
                message,
                CorrelationIdFilter.current(request),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }
}
