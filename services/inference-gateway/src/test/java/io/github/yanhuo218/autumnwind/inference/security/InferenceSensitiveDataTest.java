package io.github.yanhuo218.autumnwind.inference.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.yanhuo218.autumnwind.inference.application.ChatInferenceRequest;
import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent;
import io.github.yanhuo218.autumnwind.inference.configuration.ConversationJwtProperties;
import io.github.yanhuo218.autumnwind.inference.configuration.InferenceHttpProperties;
import io.github.yanhuo218.autumnwind.inference.configuration.InferenceSecretStoreProperties;
import io.github.yanhuo218.autumnwind.inference.configuration.InferenceServiceJwtProperties;
import io.github.yanhuo218.autumnwind.inference.configuration.ModelRegistryClientProperties;
import io.github.yanhuo218.autumnwind.inference.credentials.ResolvedCredential;
import io.github.yanhuo218.autumnwind.inference.interfaces.http.ChatCompletionRequest;
import io.github.yanhuo218.autumnwind.inference.interfaces.http.ChatMessageRequest;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderExchangeLimits;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderRequest;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceSensitiveDataTest {

    private static final UUID OWNER_ID = UUID.fromString("8fb3208f-bab0-44a5-aea7-111111111111");
    private static final UUID MODEL_ID = UUID.fromString("8fb3208f-bab0-44a5-aea7-222222222222");
    private static final UUID GENERATION_ID = UUID.fromString("8fb3208f-bab0-44a5-aea7-333333333333");
    private static final UUID ATTEMPT_ID = UUID.fromString("8fb3208f-bab0-44a5-aea7-444444444444");
    private static final UUID ENDPOINT_ID = UUID.fromString("8fb3208f-bab0-44a5-aea7-555555555555");
    private static final UUID CREDENTIAL_ID = UUID.fromString("8fb3208f-bab0-44a5-aea7-666666666666");
    private static final String MESSAGE_CANARY = "message-canary-not-for-use";
    private static final String SYSTEM_PROMPT_CANARY = "system-prompt-canary-not-for-use";
    private static final String TOKEN_CANARY = "token-canary-not-for-use";
    private static final String CREDENTIAL_CANARY = "credential-canary-not-for-use";
    private static final String URL_CANARY = "https://inference.example.invalid/sensitive-canary";
    private static final String PRIVATE_KEY_PATH_CANARY = "private-key-path-canary-not-for-use";
    private static final String PUBLIC_KEY_PATH_CANARY = "public-key-path-canary-not-for-use";
    private static final String MASTER_KEY_PATH_CANARY = "master-key-path-canary-not-for-use";
    private static final String KEY_CONTENT_CANARY = "key-content-canary-not-for-use";
    private static final List<String> SENSITIVE_CANARIES = List.of(
            MESSAGE_CANARY,
            SYSTEM_PROMPT_CANARY,
            TOKEN_CANARY,
            CREDENTIAL_CANARY,
            URL_CANARY,
            PRIVATE_KEY_PATH_CANARY,
            PUBLIC_KEY_PATH_CANARY,
            MASTER_KEY_PATH_CANARY,
            KEY_CONTENT_CANARY
    );

    @Test
    void 请求命令事件目标与配置DTO的字符串和日志不得包含敏感占位内容() throws Exception {
        List<Object> values = sensitiveValues();
        Logger logger = (Logger) LoggerFactory.getLogger("inference-sensitive-data-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);

        try {
            for (Object value : values) {
                assertDoesNotContainSensitiveCanary(value.getClass().getSimpleName() + "#toString", value.toString());
                logger.info("推理安全回归对象={}", value);
            }
            assertThat(appender.list).hasSize(values.size());
            for (ILoggingEvent event : appender.list) {
                assertDoesNotContainSensitiveCanary("捕获日志", event.getFormattedMessage());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    private static List<Object> sensitiveValues() throws Exception {
        ChatInferenceCommand.Message commandMessage = construct(
                ChatInferenceCommand.Message.class,
                new Class<?>[]{String.class, String.class},
                "user", MESSAGE_CANARY);
        ChatMessageRequest requestMessage = construct(
                ChatMessageRequest.class,
                new Class<?>[]{ChatMessageRequest.Role.class, String.class},
                ChatMessageRequest.Role.user, MESSAGE_CANARY);
        EncryptedSecret encryptedSecret = new EncryptedSecret(
                EncryptedSecret.CURRENT_VERSION,
                KEY_CONTENT_CANARY,
                new byte[12],
                new byte[48],
                new byte[12],
                CREDENTIAL_CANARY.getBytes(StandardCharsets.US_ASCII));
        InferenceTarget.Capabilities capabilities = construct(
                InferenceTarget.Capabilities.class,
                new Class<?>[]{String.class, Set.class, String.class, boolean.class, boolean.class, boolean.class,
                        int.class, int.class},
                "CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT", true, true, true, 8_192, 1_024);

        return List.of(
                construct(ChatCompletionRequest.class,
                        new Class<?>[]{UUID.class, UUID.class, UUID.class, UUID.class, List.class, String.class,
                                Double.class, Integer.class},
                        OWNER_ID, MODEL_ID, GENERATION_ID, ATTEMPT_ID, List.of(requestMessage),
                        SYSTEM_PROMPT_CANARY, 0.5, 256),
                requestMessage,
                construct(ChatInferenceRequest.class,
                        new Class<?>[]{UUID.class, UUID.class, UUID.class, UUID.class, List.class, String.class,
                                Double.class, Integer.class},
                        OWNER_ID, MODEL_ID, GENERATION_ID, ATTEMPT_ID, List.of(commandMessage),
                        SYSTEM_PROMPT_CANARY, 0.5, 256),
                construct(ChatInferenceCommand.class,
                        new Class<?>[]{UUID.class, UUID.class, List.class, String.class, Double.class, Integer.class,
                                boolean.class, String.class},
                        OWNER_ID, MODEL_ID, List.of(commandMessage), SYSTEM_PROMPT_CANARY, 0.5, 256, true,
                        "correlation-id-canary-123456"),
                commandMessage,
                construct(InferenceEvent.Reasoning.class, new Class<?>[]{String.class}, MESSAGE_CANARY),
                construct(InferenceEvent.TextDelta.class, new Class<?>[]{String.class}, MESSAGE_CANARY),
                construct(InferenceEvent.Done.class, new Class<?>[]{String.class}, MESSAGE_CANARY),
                construct(InferenceEvent.Error.class,
                        new Class<?>[]{InferenceEvent.ErrorCode.class, String.class, boolean.class},
                        InferenceEvent.ErrorCode.PROVIDER_ERROR, "correlation-id-canary-123456", false),
                construct(InferenceEvent.Usage.class, new Class<?>[]{Integer.class, Integer.class, Integer.class},
                        1, 2, 3),
                construct(InferenceEvent.Start.class, new Class<?>[]{String.class}, ATTEMPT_ID.toString()),
                construct(InferenceTarget.class,
                        new Class<?>[]{UUID.class, UUID.class, String.class, long.class, UUID.class, URI.class,
                                String.class, int.class, long.class, InferenceTarget.Capabilities.class, UUID.class,
                                EncryptedSecret.class},
                        OWNER_ID, MODEL_ID, "provider-model-canary", 1L, ENDPOINT_ID, URI.create(URL_CANARY),
                        "OPENAI_COMPATIBLE", 30, 1L, capabilities, CREDENTIAL_ID, encryptedSecret),
                construct(ValidatedTarget.class, new Class<?>[]{URI.class, List.class}, URI.create(URL_CANARY),
                        List.of(InetAddress.getByAddress(new byte[]{11, 0, 0, 1}))),
                construct(ServiceJwtRequest.class, new Class<?>[]{String.class, Set.class, UUID.class},
                        "model-registry-service", Set.of("model-registry.inference.resolve"), OWNER_ID),
                construct(ProviderRequest.class, new Class<?>[]{byte[].class, byte[].class},
                        TOKEN_CANARY.getBytes(StandardCharsets.US_ASCII),
                        CREDENTIAL_CANARY.getBytes(StandardCharsets.US_ASCII)),
                construct(ProviderFrame.class, new Class<?>[]{int.class, byte[].class}, 500,
                        CREDENTIAL_CANARY.getBytes(StandardCharsets.US_ASCII)),
                construct(ResolvedCredential.class, new Class<?>[]{byte[].class},
                        TOKEN_CANARY.getBytes(StandardCharsets.US_ASCII)),
                construct(ConversationJwtProperties.class,
                        new Class<?>[]{String.class, String.class, URI.class, Set.class, Duration.class},
                        URL_CANARY, "inference-gateway", URI.create(URL_CANARY + "/jwks"),
                        Set.of("conversation-service"), Duration.ofSeconds(60)),
                construct(InferenceServiceJwtProperties.class,
                        new Class<?>[]{String.class, Path.class, Path.class, String.class, Duration.class},
                        URL_CANARY, Path.of(PRIVATE_KEY_PATH_CANARY), Path.of(PUBLIC_KEY_PATH_CANARY),
                        "service-jwt-key", Duration.ofSeconds(60)),
                construct(InferenceSecretStoreProperties.class, new Class<?>[]{Path.class, String.class},
                        Path.of(MASTER_KEY_PATH_CANARY), "master-key-id"),
                construct(ModelRegistryClientProperties.class, new Class<?>[]{URI.class, Duration.class, boolean.class},
                        URI.create(URL_CANARY), Duration.ofSeconds(5), false),
                construct(InferenceHttpProperties.class, new Class<?>[]{int.class}, 1_024),
                construct(ProviderExchangeLimits.class,
                        new Class<?>[]{Duration.class, Duration.class, int.class, long.class},
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1_024, 4_096)
        );
    }

    private static <T> T construct(Class<T> type, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
        return constructor.newInstance(arguments);
    }

    private static void assertDoesNotContainSensitiveCanary(String source, String value) {
        for (String canary : SENSITIVE_CANARIES) {
            assertThat(value).as(source).doesNotContain(canary);
        }
    }
}
