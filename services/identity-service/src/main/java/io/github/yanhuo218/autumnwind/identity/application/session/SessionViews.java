package io.github.yanhuo218.autumnwind.identity.application.session;

import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.AuthSessionEntity;
import io.github.yanhuo218.autumnwind.identity.infrastructure.persistence.UserEntity;

final class SessionViews {

    private SessionViews() {
    }

    static SessionView from(UserEntity user, AuthSessionEntity session) {
        SessionUserView userView = new SessionUserView(
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
        return new SessionView(userView, session.createdAt(), session.expiresAt());
    }
}
