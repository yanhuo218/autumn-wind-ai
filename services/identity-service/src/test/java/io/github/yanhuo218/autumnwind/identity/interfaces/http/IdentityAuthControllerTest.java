package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.error.AuthenticationFailedException;
import io.github.yanhuo218.autumnwind.identity.application.error.InvalidSessionException;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationOptions;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationOptionsService;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationResult;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationService;
import io.github.yanhuo218.autumnwind.identity.application.session.AuthenticationService;
import io.github.yanhuo218.autumnwind.identity.application.session.LoginResult;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionService;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionUserView;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionView;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;
import io.github.yanhuo218.autumnwind.identity.infrastructure.configuration.BrowserSecurityConfiguration;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.IdentitySecurityErrorWriter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IdentityAuthController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
        BrowserSecurityConfiguration.class,
        IdentityExceptionHandler.class,
        IdentitySecurityErrorWriter.class,
        CorrelationIdFilter.class,
        SessionCookieFactory.class,
        IdentityAuthControllerTest.FixedClockConfiguration.class
})
class IdentityAuthControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final String CORRELATION_ID = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
    private static final String RAW_SESSION_TOKEN = "raw-session-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationOptionsService registrationOptionsService;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private SessionService sessionService;

    @Test
    void Csrf端点返回HttpOnlyCookie和可提交的掩码Token() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, matchesPattern("[A-Za-z0-9._-]{16,64}")))
                .andExpect(header().exists(IdentityAuthController.CSRF_HEADER_NAME))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("AW_CSRF="),
                                containsString("Path=/"),
                                containsString("Secure"),
                                containsString("HttpOnly"),
                                not(containsString("Domain="))
                        )
                ))
                .andExpect(jsonPath("$.headerName").value(IdentityAuthController.CSRF_HEADER_NAME))
                .andExpect(jsonPath("$.value").isNotEmpty())
                .andReturn();

        String headerToken = result.getResponse().getHeader(IdentityAuthController.CSRF_HEADER_NAME);
        String bodyToken = result.getResponse().getContentAsString();
        Cookie csrfCookie = result.getResponse().getCookie("AW_CSRF");
        org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);
        org.junit.jupiter.api.Assertions.assertEquals("Lax", csrfCookie.getAttribute("SameSite"));
        org.junit.jupiter.api.Assertions.assertTrue(bodyToken.contains(headerToken));
    }

    @Test
    void 注册使用真实Csrf上下文并统一返回受理结果() throws Exception {
        CsrfContext csrf = csrfContext();
        when(registrationService.register(any())).thenReturn(RegistrationResult.ACCEPTED);

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .cookie(csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "Secure-Pass-123",
                                  "displayName": "User"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(jsonPath("$.accepted").value(true));

        verify(registrationService).register(any());
    }

    @Test
    void 注册缺少Csrf或包含未知字段时分别返回403和400() throws Exception {
        String body = """
                {
                  "email": "user@example.com",
                  "password": "Secure-Pass-123",
                  "displayName": "User"
                }
                """;
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-FORBIDDEN-0002"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        CsrfContext csrf = csrfContext();
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .cookie(csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "Secure-Pass-123",
                                  "displayName": "User",
                                  "unexpected": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-VALIDATION-0001"));

        verify(registrationService, never()).register(any());
    }

    @Test
    void 登录成功设置安全SessionCookie且认证失败返回统一401() throws Exception {
        CsrfContext csrf = csrfContext();
        SessionView session = sessionView();
        when(authenticationService.login(any())).thenReturn(new LoginResult(RAW_SESSION_TOKEN, session));

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .cookie(csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"Secure-Pass-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("AW_SESSION=" + RAW_SESSION_TOKEN),
                                containsString("Path=/"),
                                containsString("Max-Age="),
                                containsString("Secure"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                not(containsString("Domain="))
                        )
                ))
                .andExpect(jsonPath("$.user.id").value(session.user().id().toString()));

        when(authenticationService.login(any())).thenThrow(new AuthenticationFailedException());
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .cookie(csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-AUTH-0001"))
                .andExpect(content().string(not(containsString("wrong-password"))));
    }

    @Test
    void 当前会话要求唯一有效SessionCookie() throws Exception {
        SessionView session = sessionView();
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(session);

        mockMvc.perform(get("/api/v1/auth/session")
                        .cookie(new Cookie(SessionCookieFactory.COOKIE_NAME, RAW_SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER"));

        mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-AUTH-0002"));

        mockMvc.perform(get("/api/v1/auth/session")
                        .cookie(
                                new Cookie(SessionCookieFactory.COOKIE_NAME, RAW_SESSION_TOKEN),
                                new Cookie(SessionCookieFactory.COOKIE_NAME, "second-token")
                        ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 注销要求Csrf并清除与登录同属性的SessionCookie() throws Exception {
        CsrfContext csrf = csrfContext();
        SessionView session = sessionView();
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(session);

        mockMvc.perform(delete("/api/v1/auth/session")
                        .cookie(csrf.cookie(), new Cookie(SessionCookieFactory.COOKIE_NAME, RAW_SESSION_TOKEN))
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("AW_SESSION="),
                                containsString("Path=/"),
                                containsString("Max-Age=0"),
                                containsString("Secure"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                not(containsString("Domain="))
                        )
                ));
        verify(sessionService).logout(RAW_SESSION_TOKEN);

        mockMvc.perform(delete("/api/v1/auth/session")
                        .cookie(new Cookie(SessionCookieFactory.COOKIE_NAME, RAW_SESSION_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-FORBIDDEN-0002"));
    }

    @Test
    void 无效SessionCookie不阻断公开登录但未知路径默认拒绝() throws Exception {
        CsrfContext csrf = csrfContext();
        when(sessionService.currentSession("invalid-token")).thenThrow(new InvalidSessionException());
        when(authenticationService.login(any())).thenReturn(new LoginResult(RAW_SESSION_TOKEN, sessionView()));

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .cookie(csrf.cookie(), new Cookie(SessionCookieFactory.COOKIE_NAME, "invalid-token"))
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"Secure-Pass-123"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 注册选项只返回五项公开策略() throws Exception {
        when(registrationOptionsService.getOptions()).thenReturn(new RegistrationOptions(
                true, false, 12, false, false
        ));

        mockMvc.perform(get("/api/v1/auth/registration-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicRegistrationEnabled").value(true))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.passwordMinimumLength").value(12))
                .andExpect(jsonPath("$.termsAcceptanceRequired").value(false))
                .andExpect(jsonPath("$.privacyAcceptanceRequired").value(false))
                .andExpect(jsonPath("$.emailDomains").doesNotExist())
                .andExpect(jsonPath("$.loginFailureLimit").doesNotExist());
    }

    @Test
    void 未处理异常只返回固定内部错误() throws Exception {
        when(registrationOptionsService.getOptions())
                .thenThrow(new IllegalStateException("不应返回给客户端的内部诊断"));

        mockMvc.perform(get("/api/v1/auth/registration-options"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-INTERNAL-0002"))
                .andExpect(jsonPath("$.message").value("服务器暂时无法处理请求。"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("不应返回给客户端的内部诊断"))));
    }

    private CsrfContext csrfContext() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("AW_CSRF");
        String token = result.getResponse().getHeader(IdentityAuthController.CSRF_HEADER_NAME);
        org.junit.jupiter.api.Assertions.assertNotNull(cookie);
        org.junit.jupiter.api.Assertions.assertNotNull(token);
        return new CsrfContext(cookie, token);
    }

    private static SessionView sessionView() {
        UUID userId = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");
        SessionUserView user = new SessionUserView(
                userId,
                "user@example.com",
                "User",
                UserRole.USER,
                AccountStatus.ACTIVE,
                true,
                NOW,
                NOW.minusSeconds(3600),
                NOW
        );
        return new SessionView(user, NOW, NOW.plusSeconds(3600));
    }

    private record CsrfContext(Cookie cookie, String token) {
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
