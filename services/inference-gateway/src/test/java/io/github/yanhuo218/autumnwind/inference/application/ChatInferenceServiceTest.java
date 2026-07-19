package io.github.yanhuo218.autumnwind.inference.application;

import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent;
import io.github.yanhuo218.autumnwind.inference.chat.OpenAiChatCompletionsAdapter;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTarget;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTargetClient;
import io.github.yanhuo218.autumnwind.security.secrets.EncryptedSecret;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatInferenceServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MODEL_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID GENERATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ATTEMPT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String CORRELATION_ID = "correlation-placeholder-0001";

    @Test
    void actor不一致时拒绝且不调用Registry() {
        InferenceTargetClient targetClient = mock(InferenceTargetClient.class);
        ChatInferenceService service = new ChatInferenceService(targetClient, mock(OpenAiChatCompletionsAdapter.class));

        assertThrows(ForbiddenActorException.class,
                () -> service.infer(requestFor(OWNER), OTHER_USER, CORRELATION_ID).blockLast());

        verifyNoInteractions(targetClient);
    }

    @Test
    void stream只由Registry能力决定() {
        InferenceTargetClient targetClient = mock(InferenceTargetClient.class);
        OpenAiChatCompletionsAdapter adapter = mock(OpenAiChatCompletionsAdapter.class);
        when(targetClient.resolve(OWNER, MODEL_ID, CORRELATION_ID)).thenReturn(Mono.just(target(false)));
        when(adapter.infer(any(), any())).thenReturn(Flux.just(new InferenceEvent.Done("stop")));
        ChatInferenceService service = new ChatInferenceService(targetClient, adapter);

        assertEquals(1, service.infer(requestFor(OWNER), OWNER, CORRELATION_ID).collectList().block().size());

        AtomicReference<ChatInferenceCommand> command = new AtomicReference<>();
        verify(adapter).infer(org.mockito.ArgumentMatchers.argThat(value -> {
            command.set(value);
            return !value.stream();
        }), any());
        assertFalse(command.get().stream());
    }

    @Test
    void 调用上下文保留生成与尝试标识且Provider命令不包含它们() {
        InferenceTargetClient targetClient = mock(InferenceTargetClient.class);
        OpenAiChatCompletionsAdapter adapter = mock(OpenAiChatCompletionsAdapter.class);
        when(targetClient.resolve(OWNER, MODEL_ID, CORRELATION_ID)).thenReturn(Mono.just(target(true)));
        ChatInferenceService service = new ChatInferenceService(targetClient, adapter);
        AtomicReference<ChatInferenceCommand> command = new AtomicReference<>();
        AtomicReference<InferenceInvocationContext> invocation = new AtomicReference<>();
        when(adapter.infer(any(), any())).thenAnswer(answer -> Flux.deferContextual(context -> {
            invocation.set(context.get(InferenceInvocationContext.REACTOR_CONTEXT_KEY));
            return Flux.just(new InferenceEvent.Start(UUID.randomUUID().toString()));
        }));

        List<InferenceEvent> events = service.infer(requestFor(OWNER), OWNER, CORRELATION_ID).collectList().block();
        assertEquals(ATTEMPT_ID.toString(),
                assertInstanceOf(InferenceEvent.Start.class, events.getFirst()).attemptId());

        verify(adapter).infer(org.mockito.ArgumentMatchers.argThat(value -> {
            command.set(value);
            return true;
        }), any());
        assertEquals(new InferenceInvocationContext(GENERATION_ID, ATTEMPT_ID), invocation.get());
        assertFalse(command.get().toString().contains(GENERATION_ID.toString()));
        assertFalse(command.get().toString().contains(ATTEMPT_ID.toString()));
    }

    @Test
    void Registry和Provider建流前异常映射为稳定依赖错误() {
        InferenceTargetClient targetClient = mock(InferenceTargetClient.class);
        OpenAiChatCompletionsAdapter adapter = mock(OpenAiChatCompletionsAdapter.class);
        when(targetClient.resolve(OWNER, MODEL_ID, CORRELATION_ID))
                .thenThrow(new IllegalStateException("sensitive-placeholder"));
        ChatInferenceService service = new ChatInferenceService(targetClient, adapter);

        assertStableDependencyError(service.infer(requestFor(OWNER), OWNER, CORRELATION_ID).blockFirst());

        reset(targetClient);
        when(targetClient.resolve(OWNER, MODEL_ID, CORRELATION_ID))
                .thenReturn(Mono.error(new IllegalStateException("sensitive-placeholder")));
        assertStableDependencyError(service.infer(requestFor(OWNER), OWNER, CORRELATION_ID).blockFirst());

        when(targetClient.resolve(OWNER, MODEL_ID, CORRELATION_ID)).thenReturn(Mono.just(target(true)));
        when(adapter.infer(any(), any())).thenReturn(Flux.error(new IllegalStateException("sensitive-placeholder")));
        assertStableDependencyError(service.infer(requestFor(OWNER), OWNER, CORRELATION_ID).blockFirst());
    }

    private static void assertStableDependencyError(InferenceEvent event) {
        InferenceEvent.Error error = assertInstanceOf(InferenceEvent.Error.class, event);
        assertEquals(InferenceEvent.ErrorCode.INTERNAL_DEPENDENCY_ERROR, error.code());
        assertEquals(CORRELATION_ID, error.correlationId());
        assertFalse(error.retryable());
        assertFalse(error.toString().contains("sensitive-placeholder"));
    }

    private static ChatInferenceRequest requestFor(UUID ownerUserId) {
        return new ChatInferenceRequest(ownerUserId, MODEL_ID, GENERATION_ID, ATTEMPT_ID,
                List.of(new ChatInferenceCommand.Message("user", "请求文本占位符")),
                null, 0.7, 128);
    }

    private static InferenceTarget target(boolean streaming) {
        return new InferenceTarget(OWNER, MODEL_ID, "provider-model-placeholder", 1,
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                URI.create("https://provider.invalid/v1"), "OPENAI_COMPATIBLE", 30, 1,
                new InferenceTarget.Capabilities("CHAT_COMPLETIONS", Set.of("TEXT"), "TEXT",
                        streaming, true, false, 4096, 1024),
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                new EncryptedSecret(1, "key-id-placeholder", new byte[12], new byte[48], new byte[12], new byte[16]));
    }
}
