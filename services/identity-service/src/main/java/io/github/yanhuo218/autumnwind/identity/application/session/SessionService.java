package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.application.error.InvalidSessionException;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.SecureTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
public class SessionService {

    private static final int MAX_TOKEN_LENGTH = 1024;

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;
    private final SecureTokenService tokenService;
    private final Clock clock;

    public SessionService(
            AuthSessionRepository authSessionRepository,
            UserRepository userRepository,
            SecureTokenService tokenService,
            Clock clock
    ) {
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "会话仓库不能为空。");
        this.userRepository = Objects.requireNonNull(userRepository, "用户仓库不能为空。");
        this.tokenService = Objects.requireNonNull(tokenService, "安全 Token 服务不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Transactional(readOnly = true)
    public SessionView currentSession(String rawSessionToken) {
        SessionAndUser sessionAndUser = findActiveSession(rawSessionToken).orElseThrow(InvalidSessionException::new);
        return SessionViews.from(sessionAndUser.user(), sessionAndUser.session());
    }

    @Transactional(readOnly = true)
    public SessionIntrospection introspect(String rawSessionToken) {
        return findActiveSession(rawSessionToken)
                .map(sessionAndUser -> new SessionIntrospection(
                        true,
                        sessionAndUser.user().id(),
                        sessionAndUser.user().role(),
                        sessionAndUser.user().status(),
                        sessionAndUser.session().expiresAt()
                ))
                .orElseGet(SessionIntrospection::inactive);
    }

    @Transactional
    public void logout(String rawSessionToken) {
        String tokenHash = hashToken(rawSessionToken).orElseThrow(InvalidSessionException::new);
        int revoked = authSessionRepository.revokeActiveByTokenHash(tokenHash, clock.instant());
        if (revoked == 0) {
            throw new InvalidSessionException();
        }
    }

    private Optional<SessionAndUser> findActiveSession(String rawSessionToken) {
        Optional<String> tokenHash = hashToken(rawSessionToken);
        if (tokenHash.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        AuthSessionEntity session = authSessionRepository.findByTokenHash(tokenHash.get()).orElse(null);
        if (session == null || !session.isActive(now)) {
            return Optional.empty();
        }
        UserEntity user = userRepository.findById(session.userId()).orElse(null);
        if (user == null || !user.status().canLogin()) {
            return Optional.empty();
        }
        return Optional.of(new SessionAndUser(session, user));
    }

    private Optional<String> hashToken(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()
                || rawSessionToken.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(tokenService.hash(rawSessionToken));
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record SessionAndUser(AuthSessionEntity session, UserEntity user) {
    }
}
