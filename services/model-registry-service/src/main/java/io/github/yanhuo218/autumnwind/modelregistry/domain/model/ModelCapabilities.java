package io.github.yanhuo218.autumnwind.modelregistry.domain.model;

import java.util.Objects;
import java.util.Set;

public record ModelCapabilities(
        ModelInterfaceType interfaceType,
        Set<InputModality> inputModalities,
        OutputModality outputModality,
        boolean streaming,
        boolean systemPrompt,
        boolean reasoning,
        int contextLength,
        int maxOutputLength
) {

    public ModelCapabilities {
        Objects.requireNonNull(interfaceType, "模型接口类型不能为空。");
        Objects.requireNonNull(inputModalities, "模型输入模态不能为空。");
        inputModalities = Set.copyOf(inputModalities);
        Objects.requireNonNull(outputModality, "模型输出模态不能为空。");
        if (inputModalities.isEmpty()) {
            throw new IllegalArgumentException("模型至少需要一种输入模态。");
        }
        if (contextLength < 1 || maxOutputLength < 1 || maxOutputLength > contextLength) {
            throw new IllegalArgumentException("模型上下文长度和最大输出长度不合法。");
        }
        validateInterfaceCapabilities(interfaceType, outputModality, streaming, systemPrompt, reasoning);
    }

    private static void validateInterfaceCapabilities(
            ModelInterfaceType interfaceType,
            OutputModality outputModality,
            boolean streaming,
            boolean systemPrompt,
            boolean reasoning
    ) {
        if (interfaceType == ModelInterfaceType.CHAT_COMPLETIONS && outputModality != OutputModality.TEXT) {
            throw new IllegalArgumentException("Chat Completions 模型必须输出文本。");
        }
        if (interfaceType == ModelInterfaceType.IMAGE_GENERATION
                && (outputModality != OutputModality.IMAGE || streaming || systemPrompt || reasoning)) {
            throw new IllegalArgumentException("图片生成模型的输出模态或行为能力组合不合法。");
        }
    }
}
