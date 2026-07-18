package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.model.ModelAdministrationService;
import io.github.yanhuo218.autumnwind.modelregistry.application.model.ModelView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/model-registry/models")
public class ModelAdministrationController {

    private final ModelAdministrationService administrationService;

    public ModelAdministrationController(ModelAdministrationService administrationService) {
        this.administrationService = administrationService;
    }

    @GetMapping
    public List<ModelView> list(Authentication authentication) {
        return administrationService.list(actorUserId(authentication));
    }

    @GetMapping("/{modelId}")
    public ModelView get(@PathVariable("modelId") UUID modelId, Authentication authentication) {
        return administrationService.get(actorUserId(authentication), modelId);
    }

    @PostMapping
    public ResponseEntity<ModelView> create(@RequestBody ModelCreateRequest request,
                                            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(administrationService.create(request.toCommand(actorUserId(authentication))));
    }

    @PutMapping("/{modelId}")
    public ModelView update(@PathVariable("modelId") UUID modelId,
                            @RequestBody ModelUpdateRequest request,
                            Authentication authentication) {
        return administrationService.update(request.toCommand(actorUserId(authentication), modelId));
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
                    // 统一转为 403，避免向客户端暴露声明解析细节。
                }
            }
        }
        throw new AccessDeniedException("Service JWT 缺少合法的操作者声明。");
    }
}
