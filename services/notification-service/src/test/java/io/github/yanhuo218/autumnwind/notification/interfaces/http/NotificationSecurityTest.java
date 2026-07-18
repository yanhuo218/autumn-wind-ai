package io.github.yanhuo218.autumnwind.notification.interfaces.http;

import io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpAdministrationService;
import io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpConfigView;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSecurityMode;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpTestStatus;
import io.github.yanhuo218.autumnwind.notification.infrastructure.configuration.NotificationSecurityConfiguration;
import io.github.yanhuo218.autumnwind.notification.infrastructure.security.NotificationSecurityErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SmtpAdministrationController.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({
        NotificationSecurityConfiguration.class,
        NotificationSecurityErrorWriter.class,
        CorrelationIdFilter.class
})
@TestPropertySource(properties = {
        "autumn-wind.notification.service-jwt.issuer=https://issuer.example",
        "autumn-wind.notification.service-jwt.audience=notification-service",
        "autumn-wind.notification.service-jwt.jwk-set-uri=https://issuer.example/.well-known/jwks.json",
        "autumn-wind.notification.service-jwt.allowed-callers=gateway-service,admin-service",
        "autumn-wind.notification.service-jwt.required-scope=unexpected.scope",
        "autumn-wind.notification.service-jwt.maximum-lifetime=PT5M"
})
class NotificationSecurityTest {

    private static final String SMTP_CONFIG_PATH = "/api/v1/admin/notification/smtp-config";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SmtpAdministrationService administrationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void 缺少ServiceJwt返回统一Bearer401() throws Exception {
        mockMvc.perform(get(SMTP_CONFIG_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-AUTH-0001"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void 写接口缺少操作者声明返回403() throws Exception {
        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.subject("gateway-service")
                                        .claim("scope", "notification.smtp.manage"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_notification.smtp.manage")))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "clearPassword":false,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-FORBIDDEN-0001"));

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 写接口缺少操作者声明时先于正文解析返回403() throws Exception {
        var authentication = jwt()
                .jwt(jwt -> jwt.subject("gateway-service")
                        .claim("scope", "notification.smtp.manage"))
                .authorities(new SimpleGrantedAuthority("SCOPE_notification.smtp.manage"));

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .with(authentication)
                        .contentType(APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-FORBIDDEN-0001"));

        mockMvc.perform(post("/api/v1/admin/notification/test-emails")
                        .with(authentication)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"unexpected":true}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-FORBIDDEN-0001"));

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
        verify(administrationService, never()).createTestEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 读取接口有正确Scope时不要求操作者声明() throws Exception {
        when(administrationService.getConfig()).thenReturn(Optional.of(new SmtpConfigView(
                "smtp.example.com",
                587,
                SmtpSecurityMode.STARTTLS,
                null,
                false,
                "noreply@example.com",
                "Autumn Wind Ai",
                SmtpTestStatus.NEVER,
                null,
                0,
                Instant.parse("2026-07-18T00:00:00Z")
        )));

        mockMvc.perform(get(SMTP_CONFIG_PATH).with(authorizedService()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("smtp.example.com"));
    }

    @Test
    void 缺少专用Scope或访问未知管理路径返回403() throws Exception {
        mockMvc.perform(get(SMTP_CONFIG_PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-FORBIDDEN-0001"));

        mockMvc.perform(get("/api/v1/admin/notification/unknown").with(authorizedService()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-FORBIDDEN-0001"));
    }

    @Test
    void 非法Bearer返回401且不暴露Decoder诊断() throws Exception {
        when(jwtDecoder.decode("invalid-service-token"))
                .thenThrow(new BadJwtException("不应返回给客户端的 JWT 诊断"));

        mockMvc.perform(get(SMTP_CONFIG_PATH)
                        .header("Authorization", "Bearer invalid-service-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-AUTH-0001"))
                .andExpect(content().string(not(containsString("不应返回给客户端的 JWT 诊断"))));
    }

    @Test
    void 非Uuid操作者声明返回403() throws Exception {
        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.subject("gateway-service")
                                        .claim("scope", "notification.smtp.manage")
                                        .claim("actor_user_id", 42))
                                .authorities(new SimpleGrantedAuthority("SCOPE_notification.smtp.manage")))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "clearPassword":false,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 非规范Uuid文本的操作者声明返回403() throws Exception {
        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .with(jwt()
                                .jwt(jwt -> jwt.subject("gateway-service")
                                        .claim("scope", "notification.smtp.manage")
                                        .claim("actor_user_id", "1-1-1-1-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_notification.smtp.manage")))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "clearPassword":false,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authorizedService() {
        return jwt()
                .jwt(jwt -> jwt.subject("gateway-service").claim("scope", "notification.smtp.manage"))
                .authorities(new SimpleGrantedAuthority("SCOPE_notification.smtp.manage"));
    }
}
