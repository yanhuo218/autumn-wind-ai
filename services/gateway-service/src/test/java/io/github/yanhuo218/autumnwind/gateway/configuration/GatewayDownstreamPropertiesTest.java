package io.github.yanhuo218.autumnwind.gateway.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewayDownstreamPropertiesTest {

    @Test
    void 接受安全的下游基础地址() {
        assertDoesNotThrow(() -> new GatewayDownstreamProperties(
                URI.create("https://identity.example/api"),
                URI.create("http://127.0.0.1:8081")));
        assertDoesNotThrow(() -> new GatewayDownstreamProperties(
                URI.create("https://registry.example"),
                URI.create("http://[::1]:8082")));
    }

    @Test
    void 拒绝解析到回环地址的HTTP主机名() {
        assertThrows(IllegalArgumentException.class, () -> new GatewayDownstreamProperties(
                URI.create("http://localhost:8081"), URI.create("https://registry.example")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/identity",
            "http://identity.example",
            "https://user@identity.example",
            "https://identity.example/api?debug=true",
            "https://identity.example/api#fragment",
            "https:///api"
    })
    void 拒绝不安全的下游基础地址(String value) {
        assertThrows(IllegalArgumentException.class, () -> new GatewayDownstreamProperties(
                URI.create(value), URI.create("https://registry.example")));
        assertThrows(IllegalArgumentException.class, () -> new GatewayDownstreamProperties(
                URI.create("https://identity.example"), URI.create(value)));
    }
}
