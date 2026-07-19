package io.github.yanhuo218.autumnwind.inference.chat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ChatInferenceCommand(
        UUID tenantId,
        UUID modelId,
        List<Message> messages,
        String systemPrompt,
        Double temperature,
        Integer maxOutputTokens,
        boolean stream,
        String correlationId
) {

    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{16,64}");

    public ChatInferenceCommand {
        Objects.requireNonNull(tenantId, "租户标识不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
        messages = List.copyOf(Objects.requireNonNull(messages, "消息列表不能为空。"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("消息列表不能为空。");
        }
        Objects.requireNonNull(correlationId, "关联标识不能为空。");
        if (!CORRELATION_ID_PATTERN.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("关联标识不合法。");
        }
    }

    @Override
    public String toString() {
        return "ChatInferenceCommand[tenantId=" + tenantId
                + ", modelId=" + modelId
                + ", messages=<REDACTED>"
                + ", systemPrompt=<REDACTED>"
                + ", temperature=" + temperature
                + ", maxOutputTokens=" + maxOutputTokens
                + ", stream=" + stream
                + ", correlationId=" + correlationId + "]";
    }

    public record Message(String role, String content) {

        @Override
        public String toString() {
            return "Message[role=" + role + ", content=<REDACTED>]";
        }
    }
}
