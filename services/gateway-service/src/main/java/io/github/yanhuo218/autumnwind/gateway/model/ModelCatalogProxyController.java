package io.github.yanhuo218.autumnwind.gateway.model;

import io.github.yanhuo218.autumnwind.gateway.security.GatewaySessionAuthenticationWebFilter;
import io.github.yanhuo218.autumnwind.gateway.security.GatewayUserPrincipal;
import io.github.yanhuo218.autumnwind.gateway.web.CorrelationIdWebFilter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import io.github.yanhuo218.autumnwind.gateway.web.ProxyResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@RestController
public final class ModelCatalogProxyController {

    private static final Set<String> RESPONSE_HEADER_WHITELIST = Set.of(
            HttpHeaders.CACHE_CONTROL.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT),
            HttpHeaders.ETAG.toLowerCase(Locale.ROOT),
            HttpHeaders.LAST_MODIFIED.toLowerCase(Locale.ROOT),
            HttpHeaders.RETRY_AFTER.toLowerCase(Locale.ROOT),
            HttpHeaders.VARY.toLowerCase(Locale.ROOT));

    private final ModelRegistryProxyClient proxyClient;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public ModelCatalogProxyController(ModelRegistryProxyClient proxyClient, GatewayErrorResponseWriter errorResponseWriter) {
        this.proxyClient = Objects.requireNonNull(proxyClient, "Model Registry 代理客户端不能为空。");
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "网关错误响应写入器不能为空。");
    }

    @GetMapping("/api/v1/model-registry/models")
    public Mono<Void> listModels(ServerWebExchange exchange) {
        Object authenticated = exchange.getAttribute(GatewaySessionAuthenticationWebFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (!(authenticated instanceof GatewayUserPrincipal principal)) {
            return errorResponseWriter.write(exchange, new GatewayException(
                    GatewayErrorCode.INVALID_SESSION, "模型目录请求缺少可信身份。"));
        }
        String correlationId = CorrelationIdWebFilter.current(exchange);
        return proxyClient.listModels(principal.userId(), correlationId)
                .flatMap(response -> writeProxyResponse(exchange, response, correlationId))
                .onErrorResume(GatewayException.class, error -> errorResponseWriter.write(exchange, error))
                .onErrorResume(error -> errorResponseWriter.write(
                        exchange,
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        GatewayErrorCode.INTERNAL_ERROR,
                        "网关暂时无法处理该请求。"));
    }

    private static Mono<Void> writeProxyResponse(ServerWebExchange exchange, ProxyResponse response, String correlationId) {
        ServerHttpResponse target = exchange.getResponse();
        target.setStatusCode(response.status());
        response.headers().forEach((name, values) -> {
            if (RESPONSE_HEADER_WHITELIST.contains(name.toLowerCase(Locale.ROOT))) {
                target.getHeaders().put(name, values);
            }
        });
        target.getHeaders().set(CorrelationIdWebFilter.HEADER_NAME, correlationId);
        return target.writeWith(Mono.just(target.bufferFactory().wrap(response.body())));
    }
}
