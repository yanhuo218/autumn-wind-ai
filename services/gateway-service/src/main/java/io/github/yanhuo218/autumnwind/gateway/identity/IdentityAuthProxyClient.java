package io.github.yanhuo218.autumnwind.gateway.identity;

import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import io.github.yanhuo218.autumnwind.gateway.web.ProxyResponse;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public interface IdentityAuthProxyClient {

    Mono<ProxyResponse> forward(HttpMethod method, String path, HttpHeaders headers, byte[] body, String correlationId);

    static IdentityAuthProxyClient webClientBacked(WebClient identityWebClient) {
        return new WebClientIdentityAuthProxyClient(identityWebClient);
    }
}

final class WebClientIdentityAuthProxyClient implements IdentityAuthProxyClient {

    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024;
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<Route> PUBLIC_ROUTES = Set.of(
            new Route(HttpMethod.GET, "/api/v1/auth/csrf"),
            new Route(HttpMethod.GET, "/api/v1/auth/registration-options"),
            new Route(HttpMethod.POST, "/api/v1/auth/registrations"),
            new Route(HttpMethod.POST, "/api/v1/auth/sessions"),
            new Route(HttpMethod.GET, "/api/v1/auth/session"),
            new Route(HttpMethod.DELETE, "/api/v1/auth/session"));
    private static final Set<String> REQUEST_HEADER_WHITELIST = Set.of(
            HttpHeaders.ACCEPT.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT),
            HttpHeaders.COOKIE.toLowerCase(Locale.ROOT),
            "x-csrf-token",
            "x-correlation-id",
            "traceparent",
            "tracestate");

    private final WebClient identityWebClient;

    WebClientIdentityAuthProxyClient(WebClient identityWebClient) {
        this.identityWebClient = Objects.requireNonNull(identityWebClient, "Identity WebClient不能为空。");
    }

    @Override
    public Mono<ProxyResponse> forward(
            HttpMethod method,
            String path,
            HttpHeaders headers,
            byte[] body,
            String correlationId
    ) {
        if (!PUBLIC_ROUTES.contains(new Route(method, path))) {
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
                        .map(entity -> {
                            byte[] responseBody = entity.getBody();
                            return new ProxyResponse(
                                    entity.getStatusCode(),
                                    entity.getHeaders(),
                                    responseBody == null ? new byte[0] : responseBody);
                        }))
                .timeout(TOTAL_TIMEOUT)
                .onErrorMap(WebClientIdentityAuthProxyClient::mapError);
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
}
