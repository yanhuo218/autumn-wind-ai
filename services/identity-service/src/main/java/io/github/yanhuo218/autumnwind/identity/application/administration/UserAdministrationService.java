package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityApplicationException;
import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionRepository;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class UserAdministrationService {

    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final Clock clock;

    public UserAdministrationService(
            UserRepository userRepository,
            AuthSessionRepository sessionRepository,
            Clock clock
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "用户仓库不能为空。");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "会话仓库不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Transactional(readOnly = true)
    public UserPage listUsers(UserListQuery query) {
        Objects.requireNonNull(query, "用户列表查询不能为空。");
        Page<UserEntity> result = userRepository.findAll(
                specification(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new UserPage(
                result.getContent().stream().map(UserAdministrationService::toView).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public UserAdminView getUser(UUID userId) {
        return toView(user(userId));
    }

    @Transactional
    public UserAdminView disableUser(UUID userId, UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "操作者不能为空。");
        UserEntity user = user(userId);
        changeStatus(user, AccountStatus.DISABLED);
        Instant now = clock.instant();
        user.disable(now);
        sessionRepository.revokeAllByUserId(user.id(), now);
        userRepository.saveAndFlush(user);
        return toView(user);
    }

    @Transactional
    public UserAdminView enableUser(UUID userId, UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "操作者不能为空。");
        UserEntity user = user(userId);
        changeStatus(user, AccountStatus.ACTIVE);
        user.enable(clock.instant());
        userRepository.saveAndFlush(user);
        return toView(user);
    }

    @Transactional
    public void revokeSessions(UUID userId, UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "操作者不能为空。");
        UserEntity user = user(userId);
        sessionRepository.revokeAllByUserId(user.id(), clock.instant());
    }

    private UserEntity user(UUID userId) {
        if (userId == null) {
            throw new IdentityApplicationException(IdentityErrorCode.USER_NOT_FOUND, "用户不存在。");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new IdentityApplicationException(
                        IdentityErrorCode.USER_NOT_FOUND,
                        "用户不存在。"
                ));
    }

    private static void changeStatus(UserEntity user, AccountStatus target) {
        if (!user.status().canTransitionTo(target)) {
            throw new IdentityApplicationException(
                    IdentityErrorCode.ACCOUNT_STATE_CONFLICT,
                    "当前账户状态不允许执行该操作。"
            );
        }
    }

    private static Specification<UserEntity> specification(UserListQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (!query.query().isBlank()) {
                String pattern = "%" + query.query().toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("displayName")), pattern)
                ));
            }
            if (query.status() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), query.status()));
            }
            return criteriaBuilder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private static UserAdminView toView(UserEntity user) {
        return new UserAdminView(
                user.id(),
                user.email(),
                user.displayName(),
                user.role(),
                user.status(),
                user.isEmailVerified(),
                user.lastLoginAt(),
                user.createdAt(),
                user.updatedAt()
        );
    }
}
