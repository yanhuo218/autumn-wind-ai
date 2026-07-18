package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.InputModality;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.OutputModality;

import java.util.Set;

public record ModelCapabilitiesRequest(
        ModelInterfaceType interfaceType,
        Set<InputModality> inputModalities,
        OutputModality outputModality,
        Boolean streaming,
        Boolean systemPrompt,
        Boolean reasoning,
        Integer contextLength,
        Integer maxOutputLength
) {

    public ModelCapabilities toDomain() {
        if (interfaceType == null || inputModalities == null || outputModality == null
                || streaming == null || systemPrompt == null || reasoning == null
                || contextLength == null || maxOutputLength == null) {
            throw new IllegalArgumentException("模型能力请求字段不完整。");
        }
        return new ModelCapabilities(interfaceType, inputModalities, outputModality, streaming,
                systemPrompt, reasoning, contextLength, maxOutputLength);
    }
}
