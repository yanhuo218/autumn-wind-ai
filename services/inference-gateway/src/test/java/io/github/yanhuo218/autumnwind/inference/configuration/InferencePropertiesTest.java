package io.github.yanhuo218.autumnwind.inference.configuration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InferencePropertiesTest {

    @Test
    void Conversation调用方必须固定且寿命不得超过六十秒() {
        assertThatThrownBy(() -> new ConversationJwtProperties(
                "https://conversation.internal", "inference-gateway",
                URI.create("https://conversation.internal/internal/v1/security/jwks"),
                Set.of("other-service"), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationJwtProperties(
                "https://conversation.internal", "inference-gateway",
                URI.create("https://conversation.internal/internal/v1/security/jwks"),
                Set.of("conversation-service"), Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ConversationJwk地址必须是安全URI() {
        assertThatThrownBy(() -> new ConversationJwtProperties(
                "https://conversation.internal", "inference-gateway",
                URI.create("https://conversation.internal/jwks?debug=true"),
                Set.of("conversation-service"), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Conversation受众签名算法和issuer必须固定且安全() {
        assertThatThrownBy(() -> new ConversationJwtProperties(
                "https://conversation.internal", "other-audience",
                URI.create("https://conversation.internal/internal/v1/security/jwks"),
                Set.of("conversation-service"), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ConversationJwtProperties.REQUIRED_JWT_ALGORITHM).isEqualTo("RS256");
        for (String issuer : List.of("conversation.internal", "http://conversation.internal",
                "https://user@conversation.internal", "https://conversation.internal?debug=true",
                "https://conversation.internal#fragment")) {
            assertThatThrownBy(() -> new ConversationJwtProperties(
                    issuer, "inference-gateway",
                    URI.create("https://conversation.internal/internal/v1/security/jwks"),
                    Set.of("conversation-service"), Duration.ofSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ServiceJwt必须声明密钥文件和KeyId() {
        assertThatThrownBy(() -> new InferenceServiceJwtProperties(
                "https://inference.internal", null, Path.of("keys/public.pem"),
                "inference-key-1", Duration.ofSeconds(60)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InferenceServiceJwtProperties(
                "https://inference.internal", Path.of("keys/private.pem"), Path.of("keys/public.pem"),
                " ", Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ServiceJwt发行方和密钥路径必须安全且非空() {
        for (String issuer : List.of("inference.internal", "http://inference.internal",
                "https://user@inference.internal", "https://inference.internal?debug=true",
                "https://inference.internal#fragment")) {
            assertThatThrownBy(() -> new InferenceServiceJwtProperties(
                    issuer, Path.of("keys/private.pem"), Path.of("keys/public.pem"),
                    "inference-key-1", Duration.ofSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new InferenceServiceJwtProperties(
                "https://inference.internal", Path.of(""), Path.of("keys/public.pem"),
                "inference-key-1", Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InferenceServiceJwtProperties(
                "https://inference.internal", Path.of("keys/private.pem"), Path.of(" "),
                "inference-key-1", Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 生产Registry地址拒绝HTTP与危险URI() {
        assertThatThrownBy(() -> new ModelRegistryClientProperties(
                URI.create("http://registry.internal"), Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRegistryClientProperties(
                URI.create("https://user@registry.internal/path?secret=x"), Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Registry超时限制为一到三十秒且测试HTTP仅限回环地址() {
        assertThatThrownBy(() -> new ModelRegistryClientProperties(
                URI.create("https://registry.internal"), Duration.ZERO, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRegistryClientProperties(
                URI.create("https://registry.internal"), Duration.ofMillis(999), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRegistryClientProperties(
                URI.create("https://registry.internal"), Duration.ofSeconds(31), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRegistryClientProperties(
                URI.create("http://registry.internal"), Duration.ofSeconds(5), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ModelRegistryClientProperties(
                URI.create("http://localhost:8080"), Duration.ofSeconds(5), true))
                .doesNotThrowAnyException();
    }

    @Test
    void SecretStore必须使用主密钥路径和KeyId() {
        assertThatThrownBy(() -> new InferenceSecretStoreProperties(null, "master-key-1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InferenceSecretStoreProperties(Path.of("keys/master.key"), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InferenceSecretStoreProperties(Path.of(""), "master-key-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InferenceSecretStoreProperties(Path.of(" "), "master-key-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void HTTP请求体不得超过一MiB硬上限() {
        assertThatThrownBy(() -> new InferenceHttpProperties(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InferenceHttpProperties(InferenceHttpProperties.HARD_MAX_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
