package io.github.yanhuo218.autumnwind.gateway.model;

import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtRequest;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import io.github.yanhuo218.autumnwind.gateway.web.ProxyResponse;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public interface ModelRegistryProxyClient {

    Mono<ProxyResponse> listModels(UUID actorUserId, String correlationId);

    static ModelRegistryProxyClient webClientBacked(
            WebClient modelRegistryWebClient,
            ServiceJwtIssuer serviceJwtIssuer,
            ObjectMapper objectMapper
    ) {
        return new WebClientModelRegistryProxyClient(modelRegistryWebClient, serviceJwtIssuer, objectMapper);
    }

    static ModelRegistryProxyClient webClientBacked(WebClient modelRegistryWebClient, ServiceJwtIssuer serviceJwtIssuer) {
        return webClientBacked(modelRegistryWebClient, serviceJwtIssuer, new ObjectMapper());
    }
}

final class WebClientModelRegistryProxyClient implements ModelRegistryProxyClient {

    private static final String MODELS_PATH = "/api/v1/model-registry/models";
    private static final String AUDIENCE = "model-registry-service";
    private static final String SCOPE = "model-registry.model.read";
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern ERROR_CODE = Pattern.compile(
            "^AW-MODEL_REGISTRY-[A-Z][A-Z0-9_]{1,31}-[0-9]{4}$");
    private static final Set<Integer> PUBLIC_ERROR_STATUSES = Set.of(401, 403, 406, 500);

    private final WebClient modelRegistryWebClient;
    private final ServiceJwtIssuer serviceJwtIssuer;
    private final ObjectMapper objectMapper;

    WebClientModelRegistryProxyClient(
            WebClient modelRegistryWebClient,
            ServiceJwtIssuer serviceJwtIssuer,
            ObjectMapper objectMapper
    ) {
        this.modelRegistryWebClient = Objects.requireNonNull(modelRegistryWebClient, "Model Registry WebClient不能为空。");
        this.serviceJwtIssuer = Objects.requireNonNull(serviceJwtIssuer, "Service JWT 签发器不能为空。");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空。");
    }

    @Override
    public Mono<ProxyResponse> listModels(UUID actorUserId, String correlationId) {
        return Mono.defer(() -> modelRegistryWebClient.get()
                        .uri(MODELS_PATH)
                        .headers(headers -> setTrustedHeaders(headers, actorUserId, correlationId))
                        .exchangeToMono(response -> response.bodyToMono(byte[].class)
                                .defaultIfEmpty(new byte[0])
                                .map(body -> validateResponse(response.headers().contentType().orElse(null), body,
                                        response.statusCode(), response.headers().asHttpHeaders()))))
                .timeout(TOTAL_TIMEOUT)
                .onErrorMap(WebClientModelRegistryProxyClient::mapError);
    }

    private void setTrustedHeaders(HttpHeaders headers, UUID actorUserId, String correlationId) {
        headers.setBearerAuth(serviceJwtIssuer.issue(ServiceJwtRequest.actor(AUDIENCE, SCOPE, actorUserId)));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Correlation-ID", correlationId);
    }

    private ProxyResponse validateResponse(
            MediaType mediaType,
            byte[] body,
            org.springframework.http.HttpStatusCode status,
            HttpHeaders headers
    ) {
        if (!isJson(mediaType)) {
            throw protocolError();
        }
        JsonNode root = readJson(body);
        int statusValue = status.value();
        if (root == null
                || (statusValue == HttpStatus.OK.value() && !root.isArray())
                || (PUBLIC_ERROR_STATUSES.contains(statusValue) && !isPublicBusinessError(root))
                || (statusValue != HttpStatus.OK.value() && !PUBLIC_ERROR_STATUSES.contains(statusValue))) {
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

    private static boolean isJson(MediaType mediaType) {
        return mediaType != null
                && MediaType.APPLICATION_JSON.getType().equalsIgnoreCase(mediaType.getType())
                && MediaType.APPLICATION_JSON.getSubtype().equalsIgnoreCase(mediaType.getSubtype());
    }

    private static boolean isPublicBusinessError(JsonNode root) {
        if (!root.isObject()) {
            return false;
        }
        JsonNode code = root.path("code");
        JsonNode message = root.path("message");
        JsonNode correlationId = root.path("correlationId");
        if (!code.isTextual() || !ERROR_CODE.matcher(code.stringValue()).matches()
                || !message.isTextual()
                || !correlationId.isTextual()
                || correlationId.stringValue().length() < 16 || correlationId.stringValue().length() > 64) {
            return false;
        }
        JsonNode fieldErrors = root.optional("fieldErrors").orElse(null);
        if (fieldErrors == null) {
            return true;
        }
        if (!fieldErrors.isArray()) {
            return false;
        }
        for (JsonNode fieldError : fieldErrors) {
            if (!fieldError.isObject()) {
                return false;
            }
        }
        return true;
    }

    private static Throwable mapError(Throwable error) {
        if (error instanceof GatewayException) {
            return error;
        }
        if (error instanceof DataBufferLimitException) {
            return protocolError();
        }
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) {
            return unavailable(error);
        }
        return unavailable(error);
    }

    private static GatewayException unavailable(Throwable cause) {
        return new GatewayException(GatewayErrorCode.MODEL_REGISTRY_UNAVAILABLE, "模型服务暂时不可用。", cause);
    }

    private static GatewayException protocolError() {
        return new GatewayException(GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR, "模型服务响应不符合网关约束。");
    }
}
