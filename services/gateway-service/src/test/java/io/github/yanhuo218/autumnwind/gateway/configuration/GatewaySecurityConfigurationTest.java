package io.github.yanhuo218.autumnwind.gateway.configuration;

import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class GatewaySecurityConfigurationTest {

    private AnnotationConfigApplicationContext context;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.register(TestWebConfiguration.class, GatewaySecurityConfiguration.class,
                GatewayErrorResponseWriter.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void 只放行六条认证路由和固定基础设施GET路由() {
        get("/api/v1/auth/csrf").expectStatus().isOk();
        get("/api/v1/auth/registration-options").expectStatus().isOk();
        post("/api/v1/auth/registrations").expectStatus().isOk();
        post("/api/v1/auth/sessions").expectStatus().isOk();
        get("/api/v1/auth/session").expectStatus().isOk();
        delete("/api/v1/auth/session").expectStatus().isOk();
        get("/internal/v1/security/jwks").expectStatus().isOk();
        get("/actuator/health").expectStatus().isOk();
        get("/actuator/info").expectStatus().isOk();
        get("/api/v1/model-registry/models").expectStatus().isOk();
    }

    @Test
    void 拒绝错误方法与未授权路由并写出公开路由错误() {
        post("/api/v1/auth/csrf")
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AW-GATEWAY-ROUTING-0001")
                .jsonPath("$.message").exists()
                .jsonPath("$.correlationId").exists();
        get("/api/v1/model-registry/models/model-1")
                .expectStatus().isForbidden();
        get("/internal/v1/auth/session-introspections")
                .expectStatus().isForbidden();
        get("/api/v1/other")
                .expectStatus().isForbidden();
    }

    @Test
    void Gateway自身不创建会话也不对认证写操作执行CSRF终审() {
        post("/api/v1/auth/sessions")
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Set-Cookie");
    }

    private WebTestClient.ResponseSpec get(String path) {
        return client.get().uri(path).exchange();
    }

    private WebTestClient.ResponseSpec post(String path) {
        return client.post().uri(path).exchange();
    }

    private WebTestClient.ResponseSpec delete(String path) {
        return client.delete().uri(path).exchange();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class TestWebConfiguration {

        @Bean
        PermittedEndpointController permittedEndpointController() {
            return new PermittedEndpointController();
        }
    }

    @RestController
    static class PermittedEndpointController {

        @GetMapping("/api/v1/auth/csrf")
        String csrf() {
            return "ok";
        }

        @GetMapping("/api/v1/auth/registration-options")
        String registrationOptions() {
            return "ok";
        }

        @PostMapping("/api/v1/auth/registrations")
        String registrations() {
            return "ok";
        }

        @PostMapping("/api/v1/auth/sessions")
        String sessions() {
            return "ok";
        }

        @GetMapping("/api/v1/auth/session")
        String session() {
            return "ok";
        }

        @DeleteMapping("/api/v1/auth/session")
        String deleteSession() {
            return "ok";
        }

        @GetMapping("/internal/v1/security/jwks")
        String jwks() {
            return "ok";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "ok";
        }

        @GetMapping("/actuator/info")
        String info() {
            return "ok";
        }

        @GetMapping("/api/v1/model-registry/models")
        String models() {
            return "ok";
        }

        @GetMapping("/api/v1/model-registry/models/model-1")
        String modelDetail() {
            return "not-allowed";
        }

        @GetMapping("/internal/v1/auth/session-introspections")
        String introspection() {
            return "not-allowed";
        }

        @GetMapping("/api/v1/other")
        String other() {
            return "not-allowed";
        }
    }
}
