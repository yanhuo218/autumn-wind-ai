package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.error.InvalidSessionException;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationOptions;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationOptionsService;
import io.github.yanhuo218.autumnwind.identity.application.registration.RegistrationService;
import io.github.yanhuo218.autumnwind.identity.application.session.AuthenticationService;
import io.github.yanhuo218.autumnwind.identity.application.session.LoginResult;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionService;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionView;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class IdentityAuthController {

    public static final String CSRF_HEADER_NAME = "X-CSRF-TOKEN";

    private final RegistrationOptionsService registrationOptionsService;
    private final RegistrationService registrationService;
    private final AuthenticationService authenticationService;
    private final SessionService sessionService;
    private final SessionCookieFactory sessionCookieFactory;

    public IdentityAuthController(
            RegistrationOptionsService registrationOptionsService,
            RegistrationService registrationService,
            AuthenticationService authenticationService,
            SessionService sessionService,
            SessionCookieFactory sessionCookieFactory
    ) {
        this.registrationOptionsService = registrationOptionsService;
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.sessionCookieFactory = sessionCookieFactory;
    }

    @GetMapping("/registration-options")
    public RegistrationOptions getRegistrationOptions() {
        return registrationOptionsService.getOptions();
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfProtectionView> getCsrfProtection(CsrfToken csrfToken) {
        CsrfProtectionView body = new CsrfProtectionView(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        );
        return ResponseEntity.ok()
                .header(CSRF_HEADER_NAME, csrfToken.getToken())
                .body(body);
    }

    @PostMapping("/registrations")
    public ResponseEntity<AcceptedView> register(@Valid @RequestBody RegisterRequest request) {
        registrationService.register(request.toCommand());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AcceptedView.acceptedResponse());
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionView> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authenticationService.login(request.toCommand());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        sessionCookieFactory.create(
                                result.rawSessionToken(),
                                result.session().expiresAt()
                        ).toString()
                )
                .body(result.session());
    }

    @GetMapping("/session")
    public SessionView getCurrentSession(@AuthenticationPrincipal SessionPrincipal principal) {
        if (principal == null) {
            throw new InvalidSessionException();
        }
        return principal.session();
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(
            @CookieValue(name = SessionCookieFactory.COOKIE_NAME) String rawSessionToken
    ) {
        sessionService.logout(rawSessionToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.clear().toString())
                .build();
    }
}
