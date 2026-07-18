package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.policy.EmailAddressNormalizer;
import io.github.yanhuo218.autumnwind.identity.domain.policy.NormalizedEmail;
import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthPolicyRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class AdminUserCreationService {

    private static final short POLICY_ID = 1;
    private static final int DISPLAY_NAME_MAX_CODE_POINTS = 80;

    private final AuthPolicyRepository policyRepository;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public AdminUserCreationService(
            AuthPolicyRepository policyRepository,
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            Clock clock
    ) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "认证策略仓库不能为空。");
        this.userRepository = Objects.requireNonNull(userRepository, "用户仓库不能为空。");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "密码 Hash 服务不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Transactional
    public UserAdminView create(AdminCreateUserCommand command) {
        Objects.requireNonNull(command, "创建用户命令不能为空。");
        AuthPolicyEntity policy = policyRepository.findById(POLICY_ID)
                .orElseThrow(() -> new IdentityApplicationException(
                        IdentityErrorCode.POLICY_UNAVAILABLE,
                        "认证策略不可用。"
                ));
        NormalizedEmail email = normalizeEmail(command.email());
        String displayName = normalizeDisplayName(command.displayName());
        if (command.role() == null) {
            throw invalidRequest("用户角色不能为空。");
        }
        if (!policy.passwordPolicy().accepts(command.password())) {
            throw invalidRequest("密码不符合当前安全策略。");
        }
        if (userRepository.existsByEmail(email.value())) {
            throw duplicateUser();
        }

        Instant now = clock.instant();
        UserEntity user = UserEntity.createByAdmin(
                UUID.randomUUID(),
                email.value(),
                passwordHasher.hash(command.password()),
                displayName,
                command.role(),
                command.emailVerified(),
                now
        );
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateUser();
        }
        return new UserAdminView(
                user.id(), user.email(), user.displayName(), user.role(), user.status(),
                user.isEmailVerified(), user.lastLoginAt(), user.createdAt(), user.updatedAt()
        );
    }

    private static NormalizedEmail normalizeEmail(String rawEmail) {
        try {
            return EmailAddressNormalizer.normalizeEmail(rawEmail);
        } catch (IllegalArgumentException exception) {
            throw invalidRequest("邮箱格式不正确。");
        }
    }

    private static String normalizeDisplayName(String rawDisplayName) {
        if (rawDisplayName == null) {
            throw invalidRequest("显示名称不能为空。");
        }
        String displayName = rawDisplayName.strip();
        if (displayName.isEmpty()
                || displayName.codePointCount(0, displayName.length()) > DISPLAY_NAME_MAX_CODE_POINTS) {
            throw invalidRequest("显示名称长度不合法。");
        }
        return displayName;
    }

    private static IdentityApplicationException invalidRequest(String message) {
        return new IdentityApplicationException(IdentityErrorCode.INVALID_REQUEST, message);
    }

    private static IdentityApplicationException duplicateUser() {
        return new IdentityApplicationException(IdentityErrorCode.USER_CONFLICT, "用户邮箱已存在。");
    }
}
