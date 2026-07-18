package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.administration.AuthPolicyAdministrationService;
import io.github.yanhuo218.autumnwind.identity.application.administration.AuthPolicyView;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class IdentityAdministrationController {

    private static final String IF_MATCH = "If-Match";

    private final AuthPolicyAdministrationService policyService;

    public IdentityAdministrationController(AuthPolicyAdministrationService policyService) {
        this.policyService = policyService;
    }

    @GetMapping("/auth-policy")
    public ResponseEntity<AuthPolicyView> getAuthPolicy() {
        AuthPolicyView view = policyService.getPolicy();
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    @PutMapping("/auth-policy")
    public ResponseEntity<AuthPolicyView> updateAuthPolicy(
            @RequestHeader(IF_MATCH) String ifMatch,
            @AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody AuthPolicyUpdateRequest request
    ) {
        AuthPolicyView view = policyService.updatePolicy(
                request.toCommand(parseVersion(ifMatch), principal.userId())
        );
        return ResponseEntity.ok().eTag(etag(view.version())).body(view);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null) {
            throw invalidIfMatch();
        }
        String value = ifMatch.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalidIfMatch();
        }
    }

    private static IdentityApplicationException invalidIfMatch() {
        return new IdentityApplicationException(IdentityErrorCode.INVALID_REQUEST, "If-Match 版本不合法。");
    }
}
