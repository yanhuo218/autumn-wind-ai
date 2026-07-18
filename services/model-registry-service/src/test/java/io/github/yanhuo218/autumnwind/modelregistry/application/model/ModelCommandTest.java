package io.github.yanhuo218.autumnwind.modelregistry.application.model;

import io.github.yanhuo218.autumnwind.modelregistry.domain.model.InputModality;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelCapabilities;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.ModelInterfaceType;
import io.github.yanhuo218.autumnwind.modelregistry.domain.model.OutputModality;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCommandTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENDPOINT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MODEL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void 规范化模型标识和显示名称() {
        CreateModelCommand command = new CreateModelCommand(
                OWNER_ID,
                ENDPOINT_ID,
                " provider-model ",
                " 主要模型 ",
                capabilities(),
                true,
                false
        );

        assertEquals("provider-model", command.providerModelId());
        assertEquals("主要模型", command.displayName());
    }

    @Test
    void 拒绝空标识非法名称和负版本() {
        assertThrows(NullPointerException.class, () -> new CreateModelCommand(
                null, ENDPOINT_ID, "model", "模型", capabilities(), true, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new CreateModelCommand(
                OWNER_ID, ENDPOINT_ID, " ", "模型", capabilities(), true, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new CreateModelCommand(
                OWNER_ID, ENDPOINT_ID, "model", "模型\r\n名称", capabilities(), true, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new UpdateModelCommand(
                OWNER_ID, MODEL_ID, "model", "模型", capabilities(), true, false, -1
        ));
    }

    private static ModelCapabilities capabilities() {
        return new ModelCapabilities(
                ModelInterfaceType.CHAT_COMPLETIONS,
                Set.of(InputModality.TEXT),
                OutputModality.TEXT,
                true,
                true,
                false,
                8_192,
                1_024
        );
    }
}
