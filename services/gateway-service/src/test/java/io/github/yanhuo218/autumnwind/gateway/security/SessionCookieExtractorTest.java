package io.github.yanhuo218.autumnwind.gateway.security;

import io.github.yanhuo218.autumnwind.gateway.web.GatewayErrorCode;
import io.github.yanhuo218.autumnwind.gateway.web.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionCookieExtractorTest {

    private final SessionCookieExtractor extractor = new SessionCookieExtractor();

    @Test
    void 提取唯一的非空AW_SESSIONCookie() {
        String session = extractor.extract(MockServerHttpRequest.get("/api/v1/model-registry/models")
                .cookie(new org.springframework.http.HttpCookie("AW_SESSION", "opaque-session-value"))
                .build());

        assertEquals("opaque-session-value", session);
    }

    @Test
    void 缺失空白或重复AW_SESSIONCookie均为无效会话() {
        assertInvalid(MockServerHttpRequest.get("/api/v1/model-registry/models").build());
        assertInvalid(MockServerHttpRequest.get("/api/v1/model-registry/models")
                .cookie(new org.springframework.http.HttpCookie("AW_SESSION", "   "))
                .build());
        assertInvalid(MockServerHttpRequest.get("/api/v1/model-registry/models")
                .cookie(new org.springframework.http.HttpCookie("AW_SESSION", "first"))
                .cookie(new org.springframework.http.HttpCookie("AW_SESSION", "second"))
                .build());
    }

    private void assertInvalid(org.springframework.http.server.reactive.ServerHttpRequest request) {
        GatewayException error = assertThrows(GatewayException.class, () -> extractor.extract(request));
        assertEquals(GatewayErrorCode.INVALID_SESSION, error.errorCode());
    }
}
