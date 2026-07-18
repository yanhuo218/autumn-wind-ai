package io.github.yanhuo218.autumnwind.notification.interfaces.http;

import io.github.yanhuo218.autumnwind.notification.application.NotificationApplicationException;
import io.github.yanhuo218.autumnwind.notification.application.NotificationErrorCode;
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

import java.util.List;

@RestControllerAdvice
public class NotificationExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationExceptionHandler.class);

    @ExceptionHandler(NotificationApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(
            NotificationApplicationException exception,
            HttpServletRequest request
    ) {
        NotificationErrorCode errorCode = exception.code();
        ApiErrorResponse body = new ApiErrorResponse(
                errorCode.value(),
                exception.getMessage(),
                CorrelationIdFilter.current(request),
                List.of()
        );
        return ResponseEntity.status(statusFor(errorCode)).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                NotificationErrorCode.INVALID_REQUEST,
                "请求正文格式不正确。",
                request,
                List.of()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                NotificationErrorCode.INVALID_REQUEST,
                "请求媒体类型不受支持。",
                request,
                List.of()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotAcceptableMediaType(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_ACCEPTABLE,
                NotificationErrorCode.INVALID_REQUEST,
                "响应媒体类型不受支持。",
                request,
                List.of()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                NotificationErrorCode.INVALID_REQUEST,
                "请求字段不合法。",
                request,
                List.of()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.FORBIDDEN,
                NotificationErrorCode.ACCESS_DENIED,
                "当前服务无权执行该操作。",
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
                "Notification 接口发生未处理异常，correlationId={}，exceptionType={}",
                CorrelationIdFilter.current(request),
                exception.getClass().getName()
        );
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                NotificationErrorCode.INTERNAL_ERROR,
                "服务器暂时无法处理请求。",
                request,
                List.of()
        );
    }

    private static HttpStatus statusFor(NotificationErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INVALID_SERVICE_TOKEN -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case SMTP_CONFIG_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SMTP_CONFIG_UNAVAILABLE, SMTP_CONFIG_VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case SMTP_SECRET_FAILURE, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            NotificationErrorCode errorCode,
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
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
