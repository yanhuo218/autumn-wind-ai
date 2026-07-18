package io.github.yanhuo218.autumnwind.modelregistry.domain.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCapabilitiesTest {

    @Test
    void 接受合法聊天能力并防御性复制输入模态() {
        Set<InputModality> inputs = EnumSet.of(InputModality.TEXT, InputModality.IMAGE);
        ModelCapabilities capabilities = new ModelCapabilities(
                ModelInterfaceType.CHAT_COMPLETIONS,
                inputs,
                OutputModality.TEXT,
                true,
                true,
                true,
                128_000,
                8_192
        );

        inputs.clear();

        assertEquals(Set.of(InputModality.TEXT, InputModality.IMAGE), capabilities.inputModalities());
        assertThrows(UnsupportedOperationException.class,
                () -> capabilities.inputModalities().add(InputModality.FILE));
    }

    @Test
    void 接受合法图片生成能力() {
        ModelCapabilities capabilities = new ModelCapabilities(
                ModelInterfaceType.IMAGE_GENERATION,
                Set.of(InputModality.TEXT, InputModality.IMAGE),
                OutputModality.IMAGE,
                false,
                false,
                false,
                16_384,
                1
        );

        assertEquals(OutputModality.IMAGE, capabilities.outputModality());
    }

    @Test
    void 拒绝空输入模态和非法长度() {
        assertThrows(IllegalArgumentException.class, () -> chat(Set.of(), 8_192, 1_024));
        assertThrows(IllegalArgumentException.class, () -> chat(Set.of(InputModality.TEXT), 0, 1_024));
        assertThrows(IllegalArgumentException.class, () -> chat(Set.of(InputModality.TEXT), 8_192, 0));
        assertThrows(IllegalArgumentException.class, () -> chat(Set.of(InputModality.TEXT), 8_192, 8_193));
    }

    @Test
    void 拒绝聊天输出图片() {
        assertThrows(IllegalArgumentException.class, () -> new ModelCapabilities(
                ModelInterfaceType.CHAT_COMPLETIONS,
                Set.of(InputModality.TEXT),
                OutputModality.IMAGE,
                true,
                true,
                false,
                8_192,
                1_024
        ));
    }

    @Test
    void 拒绝图片生成输出文本或声明聊天行为能力() {
        assertThrows(IllegalArgumentException.class, () -> image(OutputModality.TEXT, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> image(OutputModality.IMAGE, true, false, false));
        assertThrows(IllegalArgumentException.class, () -> image(OutputModality.IMAGE, false, true, false));
        assertThrows(IllegalArgumentException.class, () -> image(OutputModality.IMAGE, false, false, true));
    }

    private static ModelCapabilities chat(Set<InputModality> inputs, int contextLength, int maxOutputLength) {
        return new ModelCapabilities(
                ModelInterfaceType.CHAT_COMPLETIONS,
                inputs,
                OutputModality.TEXT,
                true,
                true,
                false,
                contextLength,
                maxOutputLength
        );
    }

    private static ModelCapabilities image(
            OutputModality output,
            boolean streaming,
            boolean systemPrompt,
            boolean reasoning
    ) {
        return new ModelCapabilities(
                ModelInterfaceType.IMAGE_GENERATION,
                Set.of(InputModality.TEXT),
                output,
                streaming,
                systemPrompt,
                reasoning,
                8_192,
                1
        );
    }
}
