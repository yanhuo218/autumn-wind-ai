package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.application.error.AuthenticationFailedException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.EmailAddressNormalizer;
import io.github.yanhuo218.autumnwind.identity.domain.policy.NormalizedEmail;
import io.github.yanhuo218.autumnwind.identity.domain.security.IssuedToken;
import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.SecureTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuthenticationTransactionService {

    private static final short POLICY_ID = 1;
    private static final int MAX_PASSWORD_CODE_POINTS = 1024;
    private static final String DUMMY_PASSWORD = "Autumn-Wind-Dummy-Password";

    private final AuthPolicyRepository authPolicyRepository;
    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordHasher passwordHasher;
    private final SecureTokenService tokenService;
    private final Clock clock;
    private final Duration sessionTtl;
    private final String dummyPasswordHash;

    public AuthenticationTransactionService(
            AuthPolicyRepository authPolicyRepository,
            UserRepository userRepository,
            AuthSessionRepository authSessionRepository,
            PasswordHasher passwordHasher,
            SecureTokenService tokenService,
            Clock clock,
            @Value("${identity.session.ttl:PT168H}") Duration sessionTtl
    ) {
        this.authPolicyRepository = Objects.requireNonNull(authPolicyRepository, "认证策略仓库不能为空。");
        this.userRepository = Objects.requireNonNull(userRepository, "用户仓库不能为空。");
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "会话仓库不能为空。");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "密码 Hash 服务不能为空。");
        this.tokenService = Objects.requireNonNull(tokenService, "安全 Token 服务不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
        this.sessionTtl = requireSessionTtl(sessionTtl);
        this.dummyPasswordHash = passwordHasher.hash(DUMMY_PASSWORD);
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public LoginResult login(LoginCommand command) {
        validateCommand(command);
        NormalizedEmail email = normalizeEmail(command.email());
        AuthPolicyEntity policy = authPolicyRepository.findById(POLICY_ID)
                .orElseThrow(AuthenticationTransactionService::policyUnavailable);
        UserEntity user = userRepository.findByEmail(email.value()).orElse(null);
        if (user == null) {
            passwordHasher.matches(command.password(), dummyPasswordHash);
            throw new AuthenticationFailedException();
        }

        Instant now = clock.instant();
        boolean passwordMatches = user.passwordMatches(passwordHasher, command.password());
        if (user.isLoginLocked(now) || !user.status().canLogin()) {
            throw new AuthenticationFailedException();
        }
        if (!passwordMatches) {
            user.recordFailedLogin(
                    policy.loginFailureLimit(),
                    Duration.ofSeconds(policy.loginLockDurationSeconds()),
                    now
            );
            throw new AuthenticationFailedException();
        }

        user.recordSuccessfulLogin(now);
        IssuedToken token = tokenService.issue();
        AuthSessionEntity session = new AuthSessionEntity(
                UUID.randomUUID(),
                user.id(),
                token.hash(),
                now.plus(sessionTtl),
                now
        );
        authSessionRepository.save(session);
        return new LoginResult(token.rawValue(), SessionViews.from(user, session));
    }

    private static void validateCommand(LoginCommand command) {
        if (command == null || command.password() == null) {
            throw invalidRequest();
        }
        int passwordLength = command.password().codePointCount(0, command.password().length());
        if (passwordLength < 1 || passwordLength > MAX_PASSWORD_CODE_POINTS) {
            throw invalidRequest();
        }
    }

    private static NormalizedEmail normalizeEmail(String rawEmail) {
        try {
            return EmailAddressNormalizer.normalizeEmail(rawEmail);
        }
        catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static Duration requireSessionTtl(Duration sessionTtl) {
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()
                || sessionTtl.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("会话有效期必须大于零且不超过 365 天。");
        }
        return sessionTtl;
    }

    private static IdentityApplicationException invalidRequest() {
        return new IdentityApplicationException(IdentityErrorCode.INVALID_REQUEST, "登录请求格式不正确。");
    }

    private static IdentityApplicationException policyUnavailable() {
        return new IdentityApplicationException(IdentityErrorCode.POLICY_UNAVAILABLE, "认证策略不可用。");
    }
}
