package io.github.yanhuo218.autumnwind.gateway.identity;

import io.github.yanhuo218.autumnwind.gateway.security.GatewayUserPrincipal;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtIssuer;
import io.github.yanhuo218.autumnwind.gateway.security.ServiceJwtRequest;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public interface IdentitySessionClient {

    Mono<GatewayUserPrincipal> introspect(String rawSession, String correlationId);

    static IdentitySessionClient webClientBacked(
            WebClient identityWebClient,
            ServiceJwtIssuer serviceJwtIssuer,
            Clock clock
    ) {
        return new WebClientIdentitySessionClient(identityWebClient, serviceJwtIssuer, clock);
    }
}

final class WebClientIdentitySessionClient implements IdentitySessionClient {

    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(2);
    private static final String INTROSPECTION_PATH = "/internal/v1/auth/session-introspections";
    private static final Set<String> ROLES = Set.of("USER", "ADMIN");

    private final WebClient identityWebClient;
    private final ServiceJwtIssuer serviceJwtIssuer;
    private final Clock clock;

    WebClientIdentitySessionClient(WebClient identityWebClient, ServiceJwtIssuer serviceJwtIssuer, Clock clock) {
        this.identityWebClient = Objects.requireNonNull(identityWebClient, "Identity WebClient不能为空。");
        this.serviceJwtIssuer = Objects.requireNonNull(serviceJwtIssuer, "Service JWT 签发器不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Override
    public Mono<GatewayUserPrincipal> introspect(String rawSession, String correlationId) {
        return identityWebClient.post()
                .uri(INTROSPECTION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.setBearerAuth(serviceJwtIssuer.issue(
                            ServiceJwtRequest.service("identity-service", "identity.session.introspect")));
                    headers.set("X-Correlation-ID", correlationId);
                })
                .bodyValue(new SessionIntrospectionRequest(rawSession))
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful() || !isJson(response.headers().contentType().orElse(null))) {
                        return Mono.error(protocolError());
                    }
                    return response.bodyToMono(SessionIntrospectionResponse.class)
                            .switchIfEmpty(Mono.error(protocolError()))
                            .map(this::toPrincipal);
                })
                .timeout(TOTAL_TIMEOUT)
                .onErrorMap(WebClientIdentitySessionClient::mapError);
    }

    private GatewayUserPrincipal toPrincipal(SessionIntrospectionResponse response) {
        if (response.active() == null) {
            throw protocolError();
        }
        if (!response.active()) {
            throw invalidSession();
        }
        if (response.userId() == null
                || response.role() == null || response.role().isBlank() || !ROLES.contains(response.role())
                || response.accountStatus() == null || response.expiresAt() == null) {
            throw protocolError();
        }
        if (!"ACTIVE".equals(response.accountStatus()) || !response.expiresAt().isAfter(clock.instant())) {
            throw invalidSession();
        }
        return new GatewayUserPrincipal(response.userId(), response.role(), response.expiresAt());
    }

    private static boolean isJson(MediaType mediaType) {
        return mediaType != null
                && MediaType.APPLICATION_JSON.getType().equalsIgnoreCase(mediaType.getType())
                && MediaType.APPLICATION_JSON.getSubtype().equalsIgnoreCase(mediaType.getSubtype());
    }

    private static Throwable mapError(Throwable error) {
        if (error instanceof GatewayException) {
            return error;
        }
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) {
            return new GatewayException(GatewayErrorCode.IDENTITY_UNAVAILABLE, "身份服务暂时不可用。");
        }
        if (error instanceof DataBufferLimitException || error instanceof DecodingException) {
            return protocolError();
        }
        return new GatewayException(GatewayErrorCode.IDENTITY_UNAVAILABLE, "身份服务暂时不可用。");
    }

    private static GatewayException invalidSession() {
        return new GatewayException(GatewayErrorCode.INVALID_SESSION, "会话无效或已过期。");
    }

    private static GatewayException protocolError() {
        return new GatewayException(GatewayErrorCode.DOWNSTREAM_PROTOCOL_ERROR, "身份服务响应不符合网关约束。");
    }
}
