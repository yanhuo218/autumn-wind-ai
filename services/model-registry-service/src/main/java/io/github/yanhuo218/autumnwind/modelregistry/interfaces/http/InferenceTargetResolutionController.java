package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.inference.InferenceTargetResolutionService;
import io.github.yanhuo218.autumnwind.modelregistry.application.inference.InferenceTargetView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/model-registry/inference-target-resolutions")
public class InferenceTargetResolutionController {

    private final InferenceTargetResolutionService service;

    public InferenceTargetResolutionController(InferenceTargetResolutionService service) {
        this.service = Objects.requireNonNull(service, "推理目标解析服务不能为空。");
    }

    @PostMapping
    public ResponseEntity<InferenceTargetView> resolve(
            Authentication authentication,
            @RequestBody InferenceTargetResolutionRequest request
    ) {
        UUID actorUserId = actorUserId(authentication);
        if (!actorUserId.equals(request.ownerUserId())) {
            throw new AccessDeniedException("Service JWT 操作者与目标所有者不一致。");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.resolve(request.ownerUserId(), request.modelId()));
    }

    private static UUID actorUserId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Object claim = jwtAuthentication.getToken().getClaims().get("actor_user_id");
            if (claim instanceof String actorUserId) {
                try {
                    UUID parsed = UUID.fromString(actorUserId);
                    if (parsed.toString().equalsIgnoreCase(actorUserId)) {
                        return parsed;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 统一转为 403，避免向调用方暴露声明解析细节。
                }
            }
        }
        throw new AccessDeniedException("Service JWT 缺少合法的操作者声明。");
    }
}
