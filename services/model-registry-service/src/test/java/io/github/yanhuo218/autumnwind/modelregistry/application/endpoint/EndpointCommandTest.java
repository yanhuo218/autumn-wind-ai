package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointProtocol;
import io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint.EndpointSettings;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EndpointCommandTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENDPOINT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void 创建与替换命令不在字符串中暴露ApiKey() {
        CreateEndpointCommand create = new CreateEndpointCommand(OWNER_ID, settings(), "not-a-real-api-key");
        ReplaceEndpointKeyCommand replace = new ReplaceEndpointKeyCommand(
                OWNER_ID,
                ENDPOINT_ID,
                "not-a-real-api-key",
                0
        );

        assertFalse(create.toString().contains("not-a-real-api-key"));
        assertFalse(replace.toString().contains("not-a-real-api-key"));
    }

    @Test
    void 拒绝空标识ApiKey和非法版本() {
        assertThrows(NullPointerException.class,
                () -> new CreateEndpointCommand(null, settings(), "key"));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateEndpointCommand(OWNER_ID, settings(), ""));
        assertThrows(NullPointerException.class,
                () -> new ReplaceEndpointKeyCommand(OWNER_ID, null, "key", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplaceEndpointKeyCommand(OWNER_ID, ENDPOINT_ID, "key", -1));
    }

    private static EndpointSettings settings() {
        return new EndpointSettings(
                "主要端点",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30),
                true
        );
    }
}
