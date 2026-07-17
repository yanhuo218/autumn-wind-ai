package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.session.SessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/auth")
public class InternalSessionController {

    private final SessionService sessionService;

    public InternalSessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/session-introspections")
    public SessionIntrospectionView introspect(@Valid @RequestBody SessionIntrospectionRequest request) {
        return SessionIntrospectionView.from(sessionService.introspect(request.sessionValue()));
    }
}
