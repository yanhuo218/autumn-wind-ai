package io.github.yanhuo218.autumnwind.gateway.identity;

import io.github.yanhuo218.autumnwind.gateway.web.CorrelationIdWebFilter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import io.github.yanhuo218.autumnwind.gateway.web.ProxyResponse;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

@RestController
public final class IdentityAuthProxyController {

    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024;
    private static final Set<String> RESPONSE_HEADER_WHITELIST = Set.of(
            HttpHeaders.CACHE_CONTROL.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_ENCODING.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_LANGUAGE.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_LENGTH.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT),
            HttpHeaders.ETAG.toLowerCase(Locale.ROOT),
            HttpHeaders.LAST_MODIFIED.toLowerCase(Locale.ROOT),
            HttpHeaders.RETRY_AFTER.toLowerCase(Locale.ROOT),
            HttpHeaders.SET_COOKIE.toLowerCase(Locale.ROOT),
            HttpHeaders.VARY.toLowerCase(Locale.ROOT),
            "x-csrf-token");

    private final IdentityAuthProxyClient proxyClient;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public IdentityAuthProxyController(
            IdentityAuthProxyClient proxyClient,
            GatewayErrorResponseWriter errorResponseWriter
    ) {
        this.proxyClient = proxyClient;
        this.errorResponseWriter = errorResponseWriter;
    }

    @GetMapping("/api/v1/auth/csrf")
    public Mono<Void> csrf(ServerWebExchange exchange) {
        return forward(exchange, HttpMethod.GET, "/api/v1/auth/csrf");
    }

    @GetMapping("/api/v1/auth/registration-options")
    public Mono<Void> registrationOptions(ServerWebExchange exchange) {
        return forward(exchange, HttpMethod.GET, "/api/v1/auth/registration-options");
    }

    @PostMapping("/api/v1/auth/registrations")
    public Mono<Void> registrations(ServerWebExchange exchange) {
        return forward(exchange, HttpMethod.POST, "/api/v1/auth/registrations");
    }

    @PostMapping("/api/v1/auth/sessions")
    public Mono<Void> sessions(ServerWebExchange exchange) {
        return forward(exchange, HttpMethod.POST, "/api/v1/auth/sessions");
    }

    @GetMapping("/api/v1/auth/session")
    public Mono<Void> session(ServerWebExchange exchange) {
        return forward(exchange, HttpMethod.GET, "/api/v1/auth/session");
    }

    @DeleteMapping("/api/v1/auth/session")
    public Mono<Void> deleteSession(ServerWebExchange exchange) {
        return forward(exchange, HttpMethod.DELETE, "/api/v1/auth/session");
    }

    private Mono<Void> forward(ServerWebExchange exchange, HttpMethod method, String path) {
        String correlationId = CorrelationIdWebFilter.current(exchange);
        return readBody(exchange)
                .flatMap(body -> proxyClient.forward(
                        method,
                        path,
                        exchange.getRequest().getHeaders(),
                        body,
                        correlationId))
                .flatMap(response -> writeProxyResponse(exchange, response, correlationId))
                .onErrorResume(GatewayException.class, error -> errorResponseWriter.write(exchange, error))
                .onErrorResume(error -> errorResponseWriter.write(
                        exchange,
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        GatewayErrorCode.INTERNAL_ERROR,
                        "网关暂时无法处理该请求。"));
    }

    private static Mono<byte[]> readBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_REQUEST_BODY_BYTES)
                .map(buffer -> {
                    try {
                        byte[] body = new byte[buffer.readableByteCount()];
                        buffer.read(body);
                        return body;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class, error -> new GatewayException(
                        GatewayErrorCode.REQUEST_TOO_LARGE,
                        "认证请求正文超过最大限制。",
                        error));
    }

    private static Mono<Void> writeProxyResponse(
            ServerWebExchange exchange,
            ProxyResponse proxyResponse,
            String correlationId
    ) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(proxyResponse.status());
        proxyResponse.headers().forEach((name, values) -> {
            if (RESPONSE_HEADER_WHITELIST.contains(name.toLowerCase(Locale.ROOT))) {
                response.getHeaders().addAll(name, values);
            }
        });
        response.getHeaders().set(CorrelationIdWebFilter.HEADER_NAME, correlationId);
        byte[] body = proxyResponse.body();
        if (body.length == 0) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
