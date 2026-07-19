package io.github.yanhuo218.autumnwind.inference.application;

import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ChatInferenceRequest(
        UUID ownerUserId,
        UUID modelId,
        UUID generationId,
        UUID invocationAttemptId,
        List<ChatInferenceCommand.Message> messages,
        String systemPrompt,
        Double temperature,
        Integer maxOutputTokens
) {

    public ChatInferenceRequest {
        Objects.requireNonNull(ownerUserId, "用户标识不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        Objects.requireNonNull(generationId, "生成标识不能为空。");
        Objects.requireNonNull(invocationAttemptId, "调用尝试标识不能为空。");
        messages = List.copyOf(Objects.requireNonNull(messages, "消息列表不能为空。"));
    }

    @Override
    public String toString() {
        return "ChatInferenceRequest[ownerUserId=" + ownerUserId
                + ", modelId=" + modelId
                + ", generationId=" + generationId
                + ", invocationAttemptId=" + invocationAttemptId
                + ", messages=<REDACTED>"
                + ", systemPrompt=<REDACTED>"
                + ", temperature=" + temperature
                + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}
