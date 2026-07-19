package io.github.yanhuo218.autumnwind.gateway.identity;

import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import io.github.yanhuo218.autumnwind.gateway.web.ProxyResponse;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public interface IdentityAuthProxyClient {

    Mono<ProxyResponse> forward(HttpMethod method, String path, HttpHeaders headers, byte[] body, String correlationId);

    static IdentityAuthProxyClient webClientBacked(WebClient identityWebClient) {
        return webClientBacked(identityWebClient, new ObjectMapper());
    }

    static IdentityAuthProxyClient webClientBacked(WebClient identityWebClient, ObjectMapper objectMapper) {
        return new WebClientIdentityAuthProxyClient(identityWebClient, objectMapper);
    }
}

final class WebClientIdentityAuthProxyClient implements IdentityAuthProxyClient {

    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024;
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern ERROR_CODE = Pattern.compile(
            "^AW-[A-Z][A-Z0-9_]{1,31}-[A-Z][A-Z0-9_]{1,31}-[0-9]{4}$");
    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._-]{16,64}$");
    private static final Set<String> USER_ROLES = Set.of("USER", "ADMIN");
    private static final Set<String> USER_STATUSES = Set.of(
            "PENDING_VERIFICATION", "ACTIVE", "DISABLED", "DELETION_PENDING", "DELETED");
    private static final Map<Route, RouteContract> ROUTES = Map.of(
            new Route(HttpMethod.GET, "/api/v1/auth/csrf"),
            new RouteContract(HttpStatus.OK, Set.of(429, 500), SuccessBody.CSRF),
            new Route(HttpMethod.GET, "/api/v1/auth/registration-options"),
            new RouteContract(HttpStatus.OK, Set.of(429, 500), SuccessBody.REGISTRATION_OPTIONS),
            new Route(HttpMethod.POST, "/api/v1/auth/registrations"),
            new RouteContract(HttpStatus.ACCEPTED, Set.of(400, 403, 429, 503, 500), SuccessBody.ACCEPTED),
            new Route(HttpMethod.POST, "/api/v1/auth/sessions"),
            new RouteContract(HttpStatus.OK, Set.of(400, 401, 403, 429, 500), SuccessBody.SESSION),
            new Route(HttpMethod.GET, "/api/v1/auth/session"),
            new RouteContract(HttpStatus.OK, Set.of(401, 500), SuccessBody.SESSION),
            new Route(HttpMethod.DELETE, "/api/v1/auth/session"),
            new RouteContract(HttpStatus.NO_CONTENT, Set.of(401, 403, 500), SuccessBody.EMPTY));
    private static final Set<String> REQUEST_HEADER_WHITELIST = Set.of(
            HttpHeaders.ACCEPT.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT),
            HttpHeaders.COOKIE.toLowerCase(Locale.ROOT),
            "x-csrf-token",
            "x-correlation-id",
            "traceparent",
            "tracestate");

    private final WebClient identityWebClient;
    private final ObjectMapper objectMapper;

    WebClientIdentityAuthProxyClient(WebClient identityWebClient) {
        this(identityWebClient, new ObjectMapper());
    }

    WebClientIdentityAuthProxyClient(WebClient identityWebClient, ObjectMapper objectMapper) {
        this.identityWebClient = Objects.requireNonNull(identityWebClient, "Identity WebClient不能为空。");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空。");
    }

    @Override
    public Mono<ProxyResponse> forward(
            HttpMethod method,
            String path,
            HttpHeaders headers,
            byte[] body,
            String correlationId
    ) {
        RouteContract contract = ROUTES.get(new Route(method, path));
        if (contract == null) {
            return Mono.error(new GatewayException(GatewayErrorCode.ROUTE_NOT_ALLOWED, "认证代理路由不允许访问。"));
        }
        if (body == null || body.length > MAX_REQUEST_BODY_BYTES) {
            return Mono.error(new GatewayException(GatewayErrorCode.REQUEST_TOO_LARGE, "认证请求正文超过最大限制。"));
        }
        return identityWebClient.method(method)
                .uri(path)
                .headers(target -> copyAllowedHeaders(headers, target, correlationId))
                .bodyValue(body)
                .exchangeToMono(response -> response.toEntity(byte[].class)
                        .map(entity -> validateResponse(contract, entity.getStatusCode(), entity.getHeaders(),
                                entity.getBody() == null ? new byte[0] : entity.getBody())))
                .timeout(TOTAL_TIMEOUT)
                .onErrorMap(WebClientIdentityAuthProxyClient::mapError);
    }

    private ProxyResponse validateResponse(
            RouteContract contract,
            org.springframework.http.HttpStatusCode status,
            HttpHeaders headers,
            byte[] body
    ) {
        int statusValue = status.value();
        if (statusValue == contract.successStatus().value()) {
            if (contract.successBody() == SuccessBody.EMPTY) {
                if (body.length != 0 || headers.getContentType() != null) {
                    throw protocolError();
                }
            } else if (!isJson(headers.getContentType()) || !contract.successBody().isValid(readJson(body))) {
                throw protocolError();
            }
            return new ProxyResponse(status, headers, body);
        }
        if (!contract.errorStatuses().contains(statusValue)
                || !isJson(headers.getContentType())
                || !isValidError(readJson(body))) {
            throw protocolError();
        }
        return new ProxyResponse(status, headers, body);
    }

    private JsonNode readJson(byte[] body) {
        try {
            return objectMapper.reader(
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(body);
        } catch (RuntimeException error) {
            throw protocolError();
        }
    }

    private static boolean isJson(org.springframework.http.MediaType mediaType) {
        return mediaType != null
                && org.springframework.http.MediaType.APPLICATION_JSON.getType().equalsIgnoreCase(mediaType.getType())
                && org.springframework.http.MediaType.APPLICATION_JSON.getSubtype().equalsIgnoreCase(mediaType.getSubtype());
    }

    private static boolean isValidError(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode code = root.path("code");
        JsonNode message = root.path("message");
        JsonNode correlationId = root.path("correlationId");
        if (!code.isTextual() || !ERROR_CODE.matcher(code.stringValue()).matches()
                || !message.isTextual() || message.stringValue().length() < 1 || message.stringValue().length() > 512
                || !correlationId.isTextual() || !CORRELATION_ID.matcher(correlationId.stringValue()).matches()) {
            return false;
        }
        JsonNode fieldErrors = root.optional("fieldErrors").orElse(null);
        if (fieldErrors == null) {
            return true;
        }
        if (!fieldErrors.isArray() || fieldErrors.size() > 100) {
            return false;
        }
        for (JsonNode fieldError : fieldErrors) {
            JsonNode field = fieldError.path("field");
            JsonNode reason = fieldError.path("reason");
            if (!fieldError.isObject()
                    || !field.isTextual() || field.stringValue().length() < 1 || field.stringValue().length() > 128
                    || !reason.isTextual() || reason.stringValue().length() < 1 || reason.stringValue().length() > 256) {
                return false;
            }
        }
        return true;
    }

    private static GatewayException protocolError() {
        return new GatewayException(GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR, "身份服务响应不符合网关约束。");
    }

    private static void copyAllowedHeaders(HttpHeaders source, HttpHeaders target, String correlationId) {
        if (source != null) {
            source.forEach((name, values) -> {
                if (REQUEST_HEADER_WHITELIST.contains(name.toLowerCase(Locale.ROOT))
                        && !"x-correlation-id".equalsIgnoreCase(name)) {
                    target.addAll(name, values);
                }
            });
        }
        target.set("X-Correlation-ID", correlationId);
    }

    private static Throwable mapError(Throwable error) {
        if (error instanceof GatewayException) {
            return error;
        }
        if (error instanceof DataBufferLimitException) {
            return new GatewayException(
                    GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR,
                    "身份服务响应超过最大限制。",
                    error);
        }
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) {
            return new GatewayException(GatewayErrorCode.IDENTITY_UNAVAILABLE, "身份服务不可用。", error);
        }
        return new GatewayException(GatewayErrorCode.IDENTITY_UNAVAILABLE, "身份服务不可用。", error);
    }

    private record Route(HttpMethod method, String path) {
    }

    private record RouteContract(HttpStatus successStatus, Set<Integer> errorStatuses, SuccessBody successBody) {
    }

    private enum SuccessBody {
        CSRF,
        REGISTRATION_OPTIONS,
        ACCEPTED,
        SESSION,
        EMPTY;

        boolean isValid(JsonNode root) {
            if (root == null || !root.isObject()) {
                return false;
            }
            return switch (this) {
                case CSRF -> hasText(root, "headerName")
                        && hasText(root, "parameterName")
                        && hasText(root, "value");
                case REGISTRATION_OPTIONS -> hasBoolean(root, "publicRegistrationEnabled")
                        && hasBoolean(root, "emailVerificationRequired")
                        && hasPasswordMinimumLength(root)
                        && hasBoolean(root, "termsAcceptanceRequired")
                        && hasBoolean(root, "privacyAcceptanceRequired");
                case ACCEPTED -> root.path("accepted").isBoolean() && root.path("accepted").booleanValue();
                case SESSION -> isValidSession(root);
                case EMPTY -> false;
            };
        }

        private static boolean isValidSession(JsonNode root) {
            JsonNode user = root.path("user");
            return user.isObject()
                    && isUuid(user.path("id"))
                    && hasTextWithMaxLength(user, "email", 320)
                    && hasTextWithMaxLength(user, "displayName", 80)
                    && isAllowedText(user.path("role"), USER_ROLES)
                    && isAllowedText(user.path("status"), USER_STATUSES)
                    && user.path("emailVerified").isBoolean()
                    && isDateTime(user.path("createdAt"))
                    && isDateTime(user.path("updatedAt"))
                    && isDateTime(root.path("createdAt"))
                    && isDateTime(root.path("expiresAt"));
        }

        private static boolean hasText(JsonNode root, String field) {
            return root.path(field).isTextual();
        }

        private static boolean hasBoolean(JsonNode root, String field) {
            return root.path(field).isBoolean();
        }

        private static boolean hasTextWithMaxLength(JsonNode root, String field, int maxLength) {
            JsonNode value = root.path(field);
            return value.isTextual() && value.stringValue().length() <= maxLength;
        }

        private static boolean isAllowedText(JsonNode value, Set<String> allowedValues) {
            return value.isTextual() && allowedValues.contains(value.stringValue());
        }

        private static boolean isUuid(JsonNode value) {
            if (!value.isTextual()) {
                return false;
            }
            try {
                UUID uuid = UUID.fromString(value.stringValue());
                return uuid.toString().equalsIgnoreCase(value.stringValue());
            } catch (IllegalArgumentException error) {
                return false;
            }
        }

        private static boolean isDateTime(JsonNode value) {
            if (!value.isTextual()) {
                return false;
            }
            try {
                OffsetDateTime.parse(value.stringValue());
                return true;
            } catch (DateTimeParseException error) {
                return false;
            }
        }

        private static boolean hasPasswordMinimumLength(JsonNode root) {
            JsonNode minimumLength = root.path("passwordMinimumLength");
            return minimumLength.isIntegralNumber()
                    && minimumLength.canConvertToInt()
                    && minimumLength.intValue() >= 12
                    && minimumLength.intValue() <= 128;
        }
    }
}
