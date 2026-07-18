package io.github.yanhuo218.autumnwind.notification.interfaces.http;

import io.github.yanhuo218.autumnwind.notification.application.NotificationApplicationException;
import io.github.yanhuo218.autumnwind.notification.application.NotificationErrorCode;
import io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpAdministrationService;
import io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpConfigView;
import io.github.yanhuo218.autumnwind.notification.application.smtp.TestEmailJobView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notification")
public class SmtpAdministrationController {

    private final SmtpAdministrationService administrationService;

    public SmtpAdministrationController(SmtpAdministrationService administrationService) {
        this.administrationService = administrationService;
    }

    @GetMapping("/smtp-config")
    public SmtpConfigView getConfig() {
        return administrationService.getConfig().orElseThrow(() -> new NotificationApplicationException(
                NotificationErrorCode.SMTP_CONFIG_NOT_FOUND,
                "SMTP 配置尚未创建。"
        ));
    }

    @PutMapping("/smtp-config")
    public SmtpConfigView updateConfig(
            @RequestBody SmtpConfigUpdateRequest request,
            Authentication authentication
    ) {
        return administrationService.updateConfig(request.toCommand(actorUserId(authentication)));
    }

    @PostMapping("/test-emails")
    public ResponseEntity<TestEmailJobView> createTestEmail(
            @RequestBody TestEmailRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        TestEmailJobView job = administrationService.createTestEmail(request.toCommand(
                actorUserId(authentication),
                CorrelationIdFilter.current(servletRequest)
        ));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    private static UUID actorUserId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Object claim = jwtAuthentication.getToken().getClaims().get("actor_user_id");
            if (claim instanceof String actorUserId) {
                try {
                    UUID parsedActorUserId = UUID.fromString(actorUserId);
                    if (!parsedActorUserId.toString().equalsIgnoreCase(actorUserId)) {
                        throw new IllegalArgumentException("操作者声明不是规范 UUID 文本。");
                    }
                    return parsedActorUserId;
                } catch (IllegalArgumentException exception) {
                    throw new AccessDeniedException("Service JWT 操作者声明无效。", exception);
                }
            }
        }
        throw new AccessDeniedException("Service JWT 缺少合法的操作者声明。");
    }
}
