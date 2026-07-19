package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.configuration.InferenceSecurityConfiguration;
import io.github.yanhuo218.autumnwind.inference.security.InferenceSecurityErrorWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class InferenceSecurityTest {

    private static final String PATH = "/internal/v1/inference/chat-completions";

    private AnnotationConfigApplicationContext context;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.register(TestWebConfiguration.class, InferenceSecurityErrorWriter.class, CorrelationIdWebFilter.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void 缺失或结构无效令牌返回不泄露内容的401() {
        client.post().uri(PATH).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-INFERENCE-AUTH-0001")
                .jsonPath("$.correlationId").exists();

        client.post().uri(PATH).header("Authorization", "Bearer malformed-placeholder")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.correlationId").exists()
                .consumeWith(result -> {
                    String body = new String(result.getResponseBodyContent());
                    org.hamcrest.MatcherAssert.assertThat(body, org.hamcrest.Matchers.not(
                            containsString("decoder-detail-placeholder")));
                    org.hamcrest.MatcherAssert.assertThat(body, org.hamcrest.Matchers.not(
                            containsString("malformed-placeholder")));
                });
    }

    @Test
    void 缺少专用Scope或操作者声明返回403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("conversation-service")
                        .claim("actor_user_id", "3fa85f64-5717-4562-b3fc-2c963f66afa6"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_other")))
                .post().uri(PATH).exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("AW-INFERENCE-FORBIDDEN-0001")
                .jsonPath("$.correlationId").exists();

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("conversation-service"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .post().uri(PATH).exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.correlationId").exists();

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("conversation-service")
                        .claim("actor_user_id", "1-1-1-1-1"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .post().uri(PATH).exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.correlationId").exists();
    }

    @Test
    void 仅允许具备专用Scope和规范操作者声明的目标POST() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("conversation-service")
                        .claim("actor_user_id", "3fa85f64-5717-4562-b3fc-2c963f66afa6"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .post().uri(PATH).exchange()
                .expectStatus().isOk();
        client.get().uri("/internal/v1/inference/other").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void 已认证调用方使用目标路径错误方法时返回403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("conversation-service")
                        .claim("actor_user_id", "3fa85f64-5717-4562-b3fc-2c963f66afa6"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .get().uri(PATH).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void 已认证调用方访问未知内部POST路径时返回403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("conversation-service")
                        .claim("actor_user_id", "3fa85f64-5717-4562-b3fc-2c963f66afa6"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_inference.chat.invoke")))
                .post().uri("/internal/v1/inference/unknown").exchange()
                .expectStatus().isForbidden();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class TestWebConfiguration {

        @Bean
        @Qualifier("conversationServiceJwtDecoder")
        ReactiveJwtDecoder conversationServiceJwtDecoder() {
            return token -> Mono.error(new BadJwtException("decoder-detail-placeholder"));
        }

        @Bean
        SecurityWebFilterChain inferenceInternalSecurityWebFilterChain(
                ServerHttpSecurity http,
                @Qualifier("conversationServiceJwtDecoder") ReactiveJwtDecoder decoder,
                InferenceSecurityErrorWriter errorWriter
        ) {
            return new InferenceSecurityConfiguration()
                    .inferenceInternalSecurityWebFilterChain(http, decoder, errorWriter);
        }

        @Bean
        InferenceEndpointController inferenceEndpointController() {
            return new InferenceEndpointController();
        }
    }

    @RestController
    static class InferenceEndpointController {

        @PostMapping(PATH)
        HttpStatus chatCompletions() {
            return HttpStatus.OK;
        }
    }
}
