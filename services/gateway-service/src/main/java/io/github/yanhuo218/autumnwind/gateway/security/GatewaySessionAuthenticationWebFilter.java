package io.github.yanhuo218.autumnwind.gateway.security;

import io.github.yanhuo218.autumnwind.gateway.identity.IdentitySessionClient;
import io.github.yanhuo218.autumnwind.gateway.web.CorrelationIdWebFilter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Objects;

public final class GatewaySessionAuthenticationWebFilter implements WebFilter {

    public static final String AUTHENTICATED_USER_ATTRIBUTE = "gateway.authenticatedUser";
    private static final String MODELS_PATH = "/api/v1/model-registry/models";

    private final SessionCookieExtractor sessionCookieExtractor;
    private final IdentitySessionClient identitySessionClient;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public GatewaySessionAuthenticationWebFilter(
            SessionCookieExtractor sessionCookieExtractor,
            IdentitySessionClient identitySessionClient,
            GatewayErrorResponseWriter errorResponseWriter
    ) {
        this.sessionCookieExtractor = Objects.requireNonNull(sessionCookieExtractor, "Session Cookie 提取器不能为空。");
        this.identitySessionClient = Objects.requireNonNull(identitySessionClient, "Identity Session 客户端不能为空。");
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "错误响应写入器不能为空。");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!matches(exchange)) {
            return chain.filter(exchange);
        }
        Mono<GatewayUserPrincipal> authenticated = Mono.defer(() -> identitySessionClient.introspect(
                        sessionCookieExtractor.extract(exchange.getRequest()),
                        CorrelationIdWebFilter.current(exchange)))
                .onErrorResume(GatewayException.class, error -> errorResponseWriter.write(exchange, error)
                        .then(Mono.empty()))
                .onErrorResume(error -> errorResponseWriter.write(
                                exchange,
                                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                GatewayErrorCode.IDENTITY_UNAVAILABLE,
                                "身份服务暂时不可用。")
                        .then(Mono.empty()));
        return authenticated.flatMap(principal -> {
            exchange.getAttributes().put(AUTHENTICATED_USER_ATTRIBUTE, principal);
            return chain.filter(exchange);
        });
    }

    private static boolean matches(ServerWebExchange exchange) {
        return HttpMethod.GET.equals(exchange.getRequest().getMethod())
                && MODELS_PATH.equals(exchange.getRequest().getPath().value());
    }
}
