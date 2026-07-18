package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestCommand;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestService;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.EndpointConnectionTestView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/model-registry/endpoints/{endpointId}/connection-tests")
public class EndpointConnectionTestController {

    private final EndpointConnectionTestService service;

    public EndpointConnectionTestController(EndpointConnectionTestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EndpointConnectionTestView> enqueue(
            @PathVariable("endpointId") UUID endpointId,
            @RequestBody EndpointConnectionTestRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        UUID actorUserId = actorUserId(authentication);
        EndpointConnectionTestView view = service.enqueue(new EndpointConnectionTestCommand(
                actorUserId,
                endpointId,
                actorUserId,
                CorrelationIdFilter.current(servletRequest),
                request.requiredVersion()
        ));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
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
