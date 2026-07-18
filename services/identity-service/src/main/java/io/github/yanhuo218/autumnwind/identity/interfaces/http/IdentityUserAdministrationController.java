package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.administration.UserAdminView;
import io.github.yanhuo218.autumnwind.identity.application.administration.AdminUserCreationService;
import io.github.yanhuo218.autumnwind.identity.application.administration.UserAdministrationService;
import io.github.yanhuo218.autumnwind.identity.application.administration.UserListQuery;
import io.github.yanhuo218.autumnwind.identity.application.administration.UserPage;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class IdentityUserAdministrationController {

    private final UserAdministrationService userService;
    private final AdminUserCreationService creationService;

    public IdentityUserAdministrationController(
            UserAdministrationService userService,
            AdminUserCreationService creationService
    ) {
        this.userService = userService;
        this.creationService = creationService;
    }

    @GetMapping
    public UserPage listUsers(
            @RequestParam(name = "query", defaultValue = "") String query,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        try {
            return userService.listUsers(new UserListQuery(query, parseStatus(status), page, size));
        } catch (IllegalArgumentException exception) {
            throw new IdentityApplicationException(IdentityErrorCode.INVALID_REQUEST, "用户分页参数不合法。");
        }
    }

    @PostMapping
    public ResponseEntity<UserAdminView> createUser(
            @AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody AdminCreateUserRequest request
    ) {
        actor(principal);
        UserAdminView view = creationService.create(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{userId}")
    public UserAdminView getUser(@PathVariable("userId") UUID userId) {
        return userService.getUser(userId);
    }

    @PostMapping("/{userId}/disable")
    public UserAdminView disableUser(
            @PathVariable("userId") UUID userId,
            @AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody AccountActionRequest request
    ) {
        return userService.disableUser(userId, actor(principal));
    }

    @PostMapping("/{userId}/enable")
    public UserAdminView enableUser(
            @PathVariable("userId") UUID userId,
            @AuthenticationPrincipal SessionPrincipal principal
    ) {
        return userService.enableUser(userId, actor(principal));
    }

    @DeleteMapping("/{userId}/sessions")
    public ResponseEntity<Void> revokeSessions(
            @PathVariable("userId") UUID userId,
            @AuthenticationPrincipal SessionPrincipal principal
    ) {
        userService.revokeSessions(userId, actor(principal));
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(SessionPrincipal principal) {
        if (principal == null) {
            throw new IdentityApplicationException(IdentityErrorCode.INVALID_SESSION, "会话无效或已过期。");
        }
        return principal.userId();
    }

    private static AccountStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        try {
            return AccountStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new IdentityApplicationException(IdentityErrorCode.INVALID_REQUEST, "用户状态不合法。");
        }
    }
}
