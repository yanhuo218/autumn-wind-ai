package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.administration.UserAdminView;
import io.github.yanhuo218.autumnwind.identity.application.administration.AdminUserCreationService;
import io.github.yanhuo218.autumnwind.identity.application.administration.UserAdministrationService;
import io.github.yanhuo218.autumnwind.identity.application.administration.UserPage;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationOptionsService;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationService;
import io.github.yanhuo218.autumnwind.identity.application.session.AuthenticationService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {IdentityUserAdministrationController.class, IdentityAuthController.class})
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
        BrowserSecurityConfiguration.class,
        IdentityExceptionHandler.class,
        IdentitySecurityErrorWriter.class,
        CorrelationIdFilter.class,
        SessionCookieFactory.class,
        IdentityUserAdministrationControllerTest.FixedClockConfiguration.class
})
class IdentityUserAdministrationControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("4c184ec5-9127-4f43-a4b9-662d5e38846b");
    private static final String RAW_SESSION_TOKEN = "admin-session-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAdministrationService userService;

    @MockitoBean
    private AdminUserCreationService creationService;

    @MockitoBean
    private RegistrationOptionsService registrationOptionsService;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private SessionService sessionService;

    @Test
    void 管理员查询用户列表返回分页结果() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(session(UserRole.ADMIN));
        when(userService.listUsers(any())).thenReturn(new UserPage(
                List.of(userView(AccountStatus.ACTIVE)),
                1,
                20,
                21,
                2
        ));

        mockMvc.perform(get("/api/v1/admin/users")
                        .cookie(sessionCookie())
                        .param("query", "example")
                        .param("status", "ACTIVE")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(21));

        verify(userService).listUsers(any());
    }

    @Test
    void 管理员创建用户返回201并读取用户详情() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(session(UserRole.ADMIN));
        when(creationService.create(any())).thenReturn(userView(AccountStatus.ACTIVE));
        when(userService.getUser(USER_ID)).thenReturn(userView(AccountStatus.ACTIVE));
        CsrfContext csrf = csrfContext();

        mockMvc.perform(post("/api/v1/admin/users")
                        .cookie(sessionCookie(), csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "Secure-Pass-123",
                                  "displayName": "User",
                                  "role": "USER",
                                  "emailVerified": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()));

        mockMvc.perform(get("/api/v1/admin/users/{userId}", USER_ID)
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"));

        verify(creationService).create(any());
        verify(userService).getUser(USER_ID);
    }

    @Test
    void 非管理员不能执行禁用且缺少Csrf时拒绝() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(session(UserRole.USER));

        mockMvc.perform(post("/api/v1/admin/users/{userId}/disable", USER_ID)
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SECURITY_REVIEW\"}"))
                .andExpect(status().isForbidden());

        verify(userService, never()).disableUser(any(), any());
    }

    @Test
    void 管理员禁用启用和撤销会话遵循Csrf边界() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(session(UserRole.ADMIN));
        when(userService.disableUser(any(), any())).thenReturn(userView(AccountStatus.DISABLED));
        when(userService.enableUser(any(), any())).thenReturn(userView(AccountStatus.ACTIVE));
        CsrfContext csrf = csrfContext();

        mockMvc.perform(post("/api/v1/admin/users/{userId}/disable", USER_ID)
                        .cookie(sessionCookie(), csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SECURITY_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/admin/users/{userId}/enable", USER_ID)
                        .cookie(sessionCookie(), csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(delete("/api/v1/admin/users/{userId}/sessions", USER_ID)
                        .cookie(sessionCookie(), csrf.cookie())
                        .header(IdentityAuthController.CSRF_HEADER_NAME, csrf.token()))
                .andExpect(status().isNoContent());

        verify(userService).disableUser(any(), any());
        verify(userService).enableUser(any(), any());
        verify(userService).revokeSessions(any(), any());
    }

    @Test
    void 非法分页参数返回400且不查询用户() throws Exception {
        when(sessionService.currentSession(RAW_SESSION_TOKEN)).thenReturn(session(UserRole.ADMIN));

        mockMvc.perform(get("/api/v1/admin/users")
                        .cookie(sessionCookie())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-IDENTITY-VALIDATION-0001"));

        verify(userService, never()).listUsers(any());
    }

    private CsrfContext csrfContext() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        return new CsrfContext(
                result.getResponse().getCookie("AW_CSRF"),
                result.getResponse().getHeader(IdentityAuthController.CSRF_HEADER_NAME)
        );
    }

    private static Cookie sessionCookie() {
        return new Cookie(SessionCookieFactory.COOKIE_NAME, RAW_SESSION_TOKEN);
    }

    private static SessionView session(UserRole role) {
        return new SessionView(
                new SessionUserView(
                        USER_ID,
                        "admin@example.com",
                        "Admin",
                        role,
                        AccountStatus.ACTIVE,
                        true,
                        NOW,
                        NOW.minusSeconds(3600),
                        NOW
                ),
                NOW,
                NOW.plusSeconds(3600)
        );
    }

    private static UserAdminView userView(AccountStatus status) {
        return new UserAdminView(
                USER_ID,
                "user@example.com",
                "User",
                UserRole.USER,
                status,
                true,
                NOW,
                NOW.minusSeconds(3600),
                NOW
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
