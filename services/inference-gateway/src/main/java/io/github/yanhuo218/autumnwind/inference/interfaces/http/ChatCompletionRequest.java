package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.application.ChatInferenceRequest;
import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.UUID;

public record ChatCompletionRequest(
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID ownerUserId,
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID modelId,
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID generationId,
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID invocationAttemptId,
        @NotEmpty @Size(max = 256) List<@NotNull @Valid ChatMessageRequest> messages,
        String systemPrompt,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @Min(1) @Max(131072) Integer maxOutputTokens
) {

    public ChatCompletionRequest {
        if (messages != null) {
            messages = List.copyOf(messages);
        }
        if (systemPrompt != null && systemPrompt.isEmpty()) {
            throw new IllegalArgumentException("系统提示词不能为空字符串。");
        }
    }

    ChatInferenceRequest toApplicationRequest() {
        return new ChatInferenceRequest(
                ownerUserId,
                modelId,
                generationId,
                invocationAttemptId,
                messages.stream().map(ChatMessageRequest::toCommandMessage).toList(),
                systemPrompt,
                temperature,
                maxOutputTokens);
    }

    @Override
    public String toString() {
        return "ChatCompletionRequest[ownerUserId=" + ownerUserId
                + ", modelId=" + modelId
                + ", generationId=" + generationId
                + ", invocationAttemptId=" + invocationAttemptId
                + ", messages=<REDACTED>"
                + ", systemPrompt=<REDACTED>"
                + ", temperature=" + temperature
                + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}
