package io.github.yanhuo218.autumnwind.inference.application;

import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;
import io.github.yanhuo218.autumnwind.inference.chat.InferenceEvent;
import io.github.yanhuo218.autumnwind.inference.chat.OpenAiChatCompletionsAdapter;
import io.github.yanhuo218.autumnwind.inference.registry.InferenceTargetClient;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.UUID;

public final class ChatInferenceService {

    private final InferenceTargetClient targetClient;
    private final OpenAiChatCompletionsAdapter adapter;

    public ChatInferenceService(InferenceTargetClient targetClient, OpenAiChatCompletionsAdapter adapter) {
        this.targetClient = Objects.requireNonNull(targetClient, "推理目标客户端不能为空。");
        this.adapter = Objects.requireNonNull(adapter, "OpenAI 推理适配器不能为空。");
    }

    public Flux<InferenceEvent> infer(
            ChatInferenceRequest request,
            UUID authenticatedActor,
            String correlationId
    ) {
        Objects.requireNonNull(request, "推理请求不能为空。");
        if (!request.ownerUserId().equals(authenticatedActor)) {
            return Flux.error(new ForbiddenActorException());
        }
        InferenceInvocationContext invocation = new InferenceInvocationContext(
                request.generationId(), request.invocationAttemptId());
        return Flux.defer(() -> targetClient.resolve(request.ownerUserId(), request.modelId(), correlationId)
                .flatMapMany(target -> adapter.infer(new ChatInferenceCommand(
                        request.ownerUserId(), request.modelId(), request.messages(), request.systemPrompt(),
                        request.temperature(), request.maxOutputTokens(), target.capabilities().streaming(), correlationId), target)))
                .map(event -> event instanceof InferenceEvent.Start
                        ? new InferenceEvent.Start(invocation.invocationAttemptId().toString())
                        : event)
                .onErrorResume(error -> Flux.just(new InferenceEvent.Error(
                        InferenceEvent.ErrorCode.INTERNAL_DEPENDENCY_ERROR, correlationId, false)))
                .contextWrite(context -> context.put(InferenceInvocationContext.REACTOR_CONTEXT_KEY, invocation));
    }
}
