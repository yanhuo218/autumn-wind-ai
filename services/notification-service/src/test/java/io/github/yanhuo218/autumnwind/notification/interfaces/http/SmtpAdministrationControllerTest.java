package io.github.yanhuo218.autumnwind.notification.interfaces.http;

import io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpAdministrationService;
import io.github.yanhuo218.autumnwind.notification.application.NotificationApplicationException;
import io.github.yanhuo218.autumnwind.notification.application.NotificationErrorCode;
import io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpConfigView;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSecurityMode;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpTestStatus;
import io.github.yanhuo218.autumnwind.notification.domain.email.EmailJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
class SmtpAdministrationControllerTest {

    private static final String SMTP_CONFIG_PATH = "/api/v1/admin/notification/smtp-config";
    private static final String TEST_EMAIL_PATH = "/api/v1/admin/notification/test-emails";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SmtpAdministrationService administrationService;

    @Test
    void 读取配置返回公开字段且不暴露密码() throws Exception {
        when(administrationService.getConfig()).thenReturn(Optional.of(new SmtpConfigView(
                "smtp.example.com",
                587,
                SmtpSecurityMode.STARTTLS,
                "mailer@example.com",
                true,
                "noreply@example.com",
                "Autumn Wind Ai",
                SmtpTestStatus.NEVER,
                null,
                2,
                Instant.parse("2026-07-18T00:00:00Z")
        )));

        mockMvc.perform(get(SMTP_CONFIG_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("smtp.example.com"))
                .andExpect(jsonPath("$.passwordConfigured").value(true))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(content().string(not(containsString("password\""))))
                .andExpect(content().string(not(containsString("credential"))));
    }

    @Test
    void 配置不存在返回统一404和关联标识() throws Exception {
        String correlationId = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
        when(administrationService.getConfig()).thenReturn(Optional.empty());

        mockMvc.perform(get(SMTP_CONFIG_PATH)
                        .header("X-Correlation-ID", correlationId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", correlationId))
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-NOT_FOUND-0001"))
                .andExpect(jsonPath("$.correlationId").value(correlationId))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void 不支持的请求媒体类型保留415而不是转成500() throws Exception {
        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-VALIDATION-0001"));

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 不支持的响应媒体类型保留406而不是转成500() throws Exception {
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

        mockMvc.perform(get(SMTP_CONFIG_PATH).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-VALIDATION-0001"));
    }

    @Test
    void 更新配置从Jwt取得操作者且响应不返回密码() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
        when(administrationService.updateConfig(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    var command = (io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpConfigUpdateCommand)
                            invocation.getArgument(0);
                    org.junit.jupiter.api.Assertions.assertEquals(actorUserId, command.actorUserId());
                    org.junit.jupiter.api.Assertions.assertEquals("smtp.example.com", command.settings().host());
                    org.junit.jupiter.api.Assertions.assertEquals("temporary-test-password", command.password());
                    return new SmtpConfigView(
                            "smtp.example.com",
                            587,
                            SmtpSecurityMode.STARTTLS,
                            "mailer@example.com",
                            true,
                            "noreply@example.com",
                            "Autumn Wind Ai",
                            SmtpTestStatus.NEVER,
                            null,
                            1,
                            Instant.parse("2026-07-18T00:00:00Z")
                    );
                });

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "username":"mailer@example.com",
                                  "password":"temporary-test-password",
                                  "clearPassword":false,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(content().string(not(containsString("temporary-test-password"))))
                .andExpect(content().string(not(containsString("\"password\""))));
    }

    @Test
    void 创建测试邮件任务返回202且不回显收件地址() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
        UUID jobId = UUID.fromString("1633fd0b-e343-4dd1-b35d-4b59634f456f");
        String correlationId = "01JZ8M4A7X4S6NR2YQF1D9K3CP";
        when(administrationService.createTestEmail(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    var command = (io.github.yanhuo218.autumnwind.notification.application.smtp.TestEmailCommand)
                            invocation.getArgument(0);
                    org.junit.jupiter.api.Assertions.assertEquals(actorUserId, command.actorUserId());
                    org.junit.jupiter.api.Assertions.assertEquals(correlationId, command.correlationId());
                    org.junit.jupiter.api.Assertions.assertEquals(4, command.expectedConfigVersion());
                    return new io.github.yanhuo218.autumnwind.notification.application.smtp.TestEmailJobView(
                            jobId,
                            EmailJobStatus.QUEUED,
                            Instant.parse("2026-07-18T00:00:00Z")
                    );
                });

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"recipient@example.com","expectedConfigVersion":4}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Correlation-ID", correlationId))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(content().string(not(containsString("recipient@example.com"))));
    }

    @Test
    void 未知请求字段返回统一400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "recipientEmail":"recipient@example.com",
                                  "expectedConfigVersion":4,
                                  "unexpected":true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-VALIDATION-0001"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        verify(administrationService, never()).createTestEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 省略配置预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 显式Null配置预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":null
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 省略测试邮件预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"recipient@example.com"}
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).createTestEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 显式Null测试邮件预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"recipient@example.com","expectedConfigVersion":null}
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).createTestEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 小数端口返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587.9,
                                  "securityMode":"STARTTLS",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 小数配置预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0.9
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 小数测试邮件预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"recipient@example.com","expectedConfigVersion":0.9}
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).createTestEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 字符串端口返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":"587",
                                  "securityMode":"STARTTLS",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 字符串配置预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":"0"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 字符串测试邮件预期版本返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"recipient@example.com","expectedConfigVersion":"0"}
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).createTestEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 字符串清除密码标志返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "clearPassword":"true",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 数字安全模式返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":0,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 数字字符串安全模式返回400且不调用应用服务() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"0",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 非法领域字段返回统一400且不暴露内部诊断() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"not-an-email","expectedConfigVersion":4}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-VALIDATION-0001"))
                .andExpect(content().string(not(containsString("ASCII 邮箱"))));

        verify(administrationService, never()).createTestEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 未处理异常返回固定500且不暴露敏感诊断() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
        when(administrationService.updateConfig(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("SecretStore temporary-test-password internal failure"));

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
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
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-INTERNAL-0001"))
                .andExpect(content().string(not(containsString("temporary-test-password"))))
                .andExpect(content().string(not(containsString("SecretStore"))));
    }

    @Test
    void 省略清除密码字段时按False更新配置() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
        when(administrationService.updateConfig(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    var command = (io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpConfigUpdateCommand)
                            invocation.getArgument(0);
                    org.junit.jupiter.api.Assertions.assertFalse(command.clearPassword());
                    return new SmtpConfigView(
                            "smtp.example.com",
                            587,
                            SmtpSecurityMode.STARTTLS,
                            null,
                            false,
                            "noreply@example.com",
                            "Autumn Wind Ai",
                            SmtpTestStatus.NEVER,
                            null,
                            1,
                            Instant.parse("2026-07-18T00:00:00Z")
                    );
                });

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void 配置版本冲突返回统一409() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
        when(administrationService.updateConfig(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new NotificationApplicationException(
                        NotificationErrorCode.SMTP_CONFIG_VERSION_CONFLICT,
                        "SMTP 配置版本不匹配。"
                ));

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "clearPassword":false,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":3
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-CONFLICT-0002"));
    }

    @Test
    void 未配置时创建测试任务返回统一409() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");
        when(administrationService.createTestEmail(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new NotificationApplicationException(
                        NotificationErrorCode.SMTP_CONFIG_UNAVAILABLE,
                        "SMTP 配置尚未创建。"
                ));

        mockMvc.perform(post(TEST_EMAIL_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"recipient@example.com","expectedConfigVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-CONFLICT-0001"));
    }

    @Test
    void 密码与清除操作同时提交返回400且不回显密码() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "password":"temporary-test-password",
                                  "clearPassword":true,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-VALIDATION-0001"))
                .andExpect(content().string(not(containsString("temporary-test-password"))));

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 显式Null密码返回400而不是按省略处理() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "password":null,
                                  "clearPassword":false,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-VALIDATION-0001"));

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 显式Null清除密码标志返回400而不是按省略处理() throws Exception {
        UUID actorUserId = UUID.fromString("f7590cc5-1e56-4a28-ac97-e58380a6d94e");

        mockMvc.perform(put(SMTP_CONFIG_PATH)
                        .principal(servicePrincipal(actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "host":"smtp.example.com",
                                  "port":587,
                                  "securityMode":"STARTTLS",
                                  "password":"temporary-test-password",
                                  "clearPassword":null,
                                  "fromAddress":"noreply@example.com",
                                  "fromName":"Autumn Wind Ai",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AW-NOTIFICATION-VALIDATION-0001"));

        verify(administrationService, never()).updateConfig(org.mockito.ArgumentMatchers.any());
    }

    private static JwtAuthenticationToken servicePrincipal(UUID actorUserId) {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        Jwt jwt = new Jwt(
                "test-service-token",
                now.minusSeconds(30),
                now.plusSeconds(240),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "admin-service",
                        "scope", "notification.smtp.manage",
                        "actor_user_id", actorUserId.toString(),
                        "aud", List.of("notification-service")
                )
        );
        return new JwtAuthenticationToken(jwt);
    }
}
