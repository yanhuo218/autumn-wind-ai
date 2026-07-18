package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.administration.AuthPolicyAdministrationService;
import io.github.yanhuo218.autumnwind.identity.application.administration.AuthPolicyView;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationOptionsService;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationService;
import io.github.yanhuo218.autumnwind.identity.application.session.AuthenticationService;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionService;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionUserView;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionView;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;
import io.github.yanhuo218.autumnwind.identity.domain.policy.DomainPolicyMode;
import io.github.yanhuo218.autumnwind.identity.infrastructure.configuration.BrowserSecurityConfiguration;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.IdentitySecurityErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.servlet.http.Cookie;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {IdentityAdministrationController.class, IdentityAuthController.class})
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
        BrowserSecurityConfiguration.class,
        IdentityExceptionHandler.class,
        IdentitySecurityErrorWriter.class,
        CorrelationIdFilter.class,
        SessionCookieFactory.class,
        IdentityAdministrationControllerTest.FixedClockConfiguration.class
})
class IdentityAdministrationControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final String RAW_SESSION_TOKEN = "admin-session-token";
    private static final UUID ADMIN_ID = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthPolicyAdministrationService policyService;

    @MockitoBean
    private RegistrationOptionsService registrationOptionsService;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private SessionService sessionService;

    @Test
    void 管理员读取认证策略返回ETag和完整视图() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(adminSession());
        when(policyService.getPolicy()).thenReturn(policyView());

        mockMvc.perform(get("/api/v1/admin/auth-policy")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.publicRegistrationEnabled").value(true))
                .andExpect(jsonPath("$.emailDomains[0]").value("example.com"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void 管理员更新认证策略要求IfMatch和Csrf并返回新ETag() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(adminSession());
        when(policyService.updatePolicy(any())).thenReturn(policyView());
        CsrfContext csrf = csrfContext();

        mockMvc.perform(put("/api/v1/admin/auth-policy")
                        .cookie(sessionCookie(), csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicRegistrationEnabled": true,
                                  "emailVerificationRequired": false,
                                  "emailDomainPolicyMode": "ALLOWLIST",
                                  "emailDomains": ["example.com"],
                                  "verificationValidityMinutes": 60,
                                  "verificationResendCooldownSeconds": 30,
                                  "verificationFailureLimit": 5,
                                  "passwordMinimumLength": 12,
                                  "loginFailureLimit": 5,
                                  "loginLockMinutes": 15,
                                  "termsAcceptanceRequired": false,
                                  "privacyAcceptanceRequired": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""));

        verify(policyService).updatePolicy(any());
    }

    @Test
    void 非管理员不能读取管理策略且版本冲突返回409() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(userSession());

        mockMvc.perform(get("/api/v1/admin/auth-policy")
                        .cookie(sessionCookie()))
                .andExpect(status().isForbidden());

        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(adminSession());
        when(policyService.updatePolicy(any())).thenThrow(new IdentityApplicationException(
                IdentityErrorCode.VERSION_CONFLICT,
                "认证策略已被其他管理员更新。"
        ));
        CsrfContext csrf = csrfContext();
        mockMvc.perform(put("/api/v1/admin/auth-policy")
                        .cookie(sessionCookie(), csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicRegistrationEnabled": true,
                                  "emailVerificationRequired": false,
                                  "emailDomainPolicyMode": "ALLOWLIST",
                                  "emailDomains": ["example.com"],
                                  "verificationValidityMinutes": 60,
                                  "verificationResendCooldownSeconds": 30,
                                  "verificationFailureLimit": 5,
                                  "passwordMinimumLength": 12,
                                  "loginFailureLimit": 5,
                                  "loginLockMinutes": 15,
                                  "termsAcceptanceRequired": false,
                                  "privacyAcceptanceRequired": false
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-CONFLICT-0001"));

        verify(policyService).updatePolicy(any());
    }

    @Test
    void 非法邮箱域返回400且不调用应用服务() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(adminSession());
        CsrfContext csrf = csrfContext();

        mockMvc.perform(put("/api/v1/admin/auth-policy")
                        .cookie(sessionCookie(), csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicRegistrationEnabled": true,
                                  "emailVerificationRequired": false,
                                  "emailDomainPolicyMode": "ALLOWLIST",
                                  "emailDomains": ["bad domain"],
                                  "verificationValidityMinutes": 60,
                                  "verificationResendCooldownSeconds": 30,
                                  "verificationFailureLimit": 5,
                                  "passwordMinimumLength": 12,
                                  "loginFailureLimit": 5,
                                  "loginLockMinutes": 15,
                                  "termsAcceptanceRequired": false,
                                  "privacyAcceptanceRequired": false
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-VALIDATION-0001"));

        verify(policyService, never()).updatePolicy(any());
    }

    private CsrfContext csrfContext() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("AW_CSRF");
        String token = result.getResponse().getHeader(IdentityAuthController.CSRF_HEADER_NAME);
        return new CsrfContext(cookie, token);
    }

    private static Cookie sessionCookie() {
        return new Cookie(SessionCookieFactory.COOKIE_NAME, RAW_SESSION_TOKEN);
    }

    private static SessionView adminSession() {
        return session(UserRole.ADMIN);
    }

    private static SessionView userSession() {
        return session(UserRole.USER);
    }

    private static SessionView session(UserRole role) {
        SessionUserView user = new SessionUserView(
                ADMIN_ID,
                "admin@example.com",
                "Admin",
                role,
                AccountStatus.ACTIVE,
                true,
                NOW,
                NOW.minusSeconds(3600),
                NOW
        );
        return new SessionView(user, NOW, NOW.plusSeconds(3600));
    }

    private static AuthPolicyView policyView() {
        return new AuthPolicyView(
                true,
                false,
                DomainPolicyMode.ALLOWLIST,
                Set.of("example.com"),
                60,
                30,
                5,
                12,
                5,
                15,
                false,
                false,
                3,
                NOW,
                ADMIN_ID
        );
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
