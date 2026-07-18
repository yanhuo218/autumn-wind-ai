package io.github.yanhuo218.autumnwind.modelregistry.domain.endpoint;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EndpointSettingsTest {

    @Test
    void 规范化合法端点设置() {
        EndpointSettings settings = new EndpointSettings(
                " 主要端点 ",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30),
                true
        );

        assertEquals("主要端点", settings.displayName());
        assertEquals(URI.create("https://api.example.com/v1"), settings.baseUrl());
        assertEquals(Duration.ofSeconds(30), settings.requestTimeout());
    }

    @Test
    void 拒绝非Https和缺少主机的地址() {
        assertThrows(IllegalArgumentException.class, () -> settings("http://api.example.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> settings("https:///v1"));
        assertThrows(IllegalArgumentException.class, () -> settings("/v1"));
    }

    @Test
    void 拒绝包含凭据查询或片段的地址() {
        assertThrows(IllegalArgumentException.class, () -> settings("https://user:pass@example.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> settings("https://api.example.com/v1?debug=true"));
        assertThrows(IllegalArgumentException.class, () -> settings("https://api.example.com/v1#section"));
    }

    @Test
    void 拒绝非法名称协议和超时() {
        assertThrows(IllegalArgumentException.class, () -> new EndpointSettings(
                " ",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30),
                true
        ));
        assertThrows(IllegalArgumentException.class, () -> new EndpointSettings(
                "端点\r\n名称",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30),
                true
        ));
        assertThrows(NullPointerException.class, () -> new EndpointSettings(
                "主要端点",
                URI.create("https://api.example.com/v1"),
                null,
                Duration.ofSeconds(30),
                true
        ));
        assertThrows(IllegalArgumentException.class, () -> settingsWithTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> settingsWithTimeout(Duration.ofSeconds(121)));
    }

    private static EndpointSettings settings(String url) {
        return new EndpointSettings(
                "主要端点",
                URI.create(url),
                EndpointProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(30),
                true
        );
    }

    private static EndpointSettings settingsWithTimeout(Duration timeout) {
        return new EndpointSettings(
                "主要端点",
                URI.create("https://api.example.com/v1"),
                EndpointProtocol.OPENAI_COMPATIBLE,
                timeout,
                true
        );
    }
}
