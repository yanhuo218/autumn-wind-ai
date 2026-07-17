package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.session.SessionIntrospection;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionService;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;
import io.github.yanhuo218.autumnwind.identity.infrastructure.configuration.InternalSecurityConfiguration;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.IdentitySecurityErrorWriter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalSessionController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
        InternalSecurityConfiguration.class,
        IdentityExceptionHandler.class,
        IdentitySecurityErrorWriter.class,
        CorrelationIdFilter.class
})
@TestPropertySource(properties = {
        "autumn-wind.identity.service-jwt.issuer=https://issuer.example",
        "autumn-wind.identity.service-jwt.audience=identity-service",
        "autumn-wind.identity.service-jwt.jwk-set-uri=https://issuer.example/.well-known/jwks.json",
        "autumn-wind.identity.service-jwt.allowed-callers=gateway-service,conversation-service",
        "autumn-wind.identity.service-jwt.required-scope=identity.session.introspect",
        "autumn-wind.identity.service-jwt.maximum-lifetime=PT5M"
})
class InternalSessionControllerTest {

    private static final String PATH = "/internal/v1/auth/session-introspections";
    private static final String RAW_SESSION_TOKEN = "raw-session-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void 有效ServiceJwt无需Csrf即可返回活动会话() throws Exception {
        UUID userId = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");
        Instant expiresAt = Instant.parse("2026-07-19T00:00:00Z");
        when(sessionService.introspect(RAW_SESSION_TOKEN)).thenReturn(new SessionIntrospection(
                true,
                userId,
                UserRole.USER,
                AccountStatus.ACTIVE,
                expiresAt
        ));

        mockMvc.perform(post(PATH)
                        .with(authorizedService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionValue":"raw-session-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));
    }

    @Test
    void 无效会话只返回Inactive且省略身份字段() throws Exception {
        when(sessionService.introspect(RAW_SESSION_TOKEN)).thenReturn(SessionIntrospection.inactive());

        mockMvc.perform(post(PATH)
                        .with(authorizedService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionValue":"raw-session-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.accountStatus").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @Test
    void 缺少ServiceJwt或仅有浏览器Cookie均返回401() throws Exception {
        String requestBody = "{\"sessionValue\":\"raw-session-token\"}";
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-AUTH-0003"));

        mockMvc.perform(post(PATH)
                        .cookie(new Cookie(SessionCookieFactory.COOKIE_NAME, RAW_SESSION_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized());

        verify(sessionService, never()).introspect(anyString());
    }

    @Test
    void 非法Bearer返回401且不暴露Decoder诊断() throws Exception {
        when(jwtDecoder.decode("invalid-service-token"))
                .thenThrow(new BadJwtException("不应返回给客户端的 JWT 诊断"));

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionValue\":\"raw-session-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-AUTH-0003"))
                .andExpect(content().string(not(containsString("不应返回给客户端的 JWT 诊断"))));
    }

    @Test
    void 缺少专用Scope或访问未知内部路径均返回403() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionValue\":\"raw-session-token\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-FORBIDDEN-0002"));

        mockMvc.perform(post("/internal/v1/unknown")
                        .with(authorizedService()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未知请求字段返回400且不调用应用服务() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(authorizedService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionValue":"raw-session-token","unexpected":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-VALIDATION-0001"));

        verify(sessionService, never()).introspect(anyString());
    }

    @Test
    void 请求对象不会输出原始会话值() {
        assertFalse(new SessionIntrospectionRequest(RAW_SESSION_TOKEN).toString().contains(RAW_SESSION_TOKEN));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authorizedService() {
        return jwt()
                .jwt(jwt -> jwt.subject("gateway-service").claim("scope", "identity.session.introspect"))
                .authorities(new SimpleGrantedAuthority("SCOPE_identity.session.introspect"));
    }
}
