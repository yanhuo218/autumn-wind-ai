package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import io.github.yanhuo218.autumnwind.inference.chat.ChatInferenceCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatMessageRequest(
        @NotNull Role role,
        @NotBlank String content
) {

    ChatInferenceCommand.Message toCommandMessage() {
        return new ChatInferenceCommand.Message(role.name(), content);
    }

    public enum Role {
        user,
        assistant
    }
}
