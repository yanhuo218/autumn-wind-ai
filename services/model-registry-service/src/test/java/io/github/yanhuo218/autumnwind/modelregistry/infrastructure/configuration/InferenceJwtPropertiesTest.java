package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InferenceJwtPropertiesTest {

    @Test
    void 推理Issuer和Jwk地址必须由环境变量提供() throws IOException {
        String configuration = new ClassPathResource("application.yaml").getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(configuration)
                .contains("issuer: ${MODEL_REGISTRY_INFERENCE_JWT_ISSUER}")
                .contains("jwk-set-uri: ${MODEL_REGISTRY_INFERENCE_JWT_JWK_SET_URI}");
    }

    @Test
    void 仅允许推理网关作为内部调用方() {
        assertThatThrownBy(() -> new InferenceJwtProperties(
                "https://inference.internal", "model-registry-service",
                URI.create("https://inference.internal/internal/v1/security/jwks"),
                Set.of("gateway-service"), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
