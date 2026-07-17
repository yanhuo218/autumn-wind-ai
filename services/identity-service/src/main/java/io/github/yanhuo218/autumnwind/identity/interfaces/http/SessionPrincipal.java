package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import io.github.yanhuo218.autumnwind.identity.application.session.SessionView;
import io.github.yanhuo218.autumnwind.identity.domain.account.UserRole;

import java.util.Objects;
import java.util.UUID;

public record SessionPrincipal(UUID userId, UserRole role, SessionView session) {

    public SessionPrincipal {
        Objects.requireNonNull(userId, "用户标识不能为空。");
        Objects.requireNonNull(role, "用户角色不能为空。");
        Objects.requireNonNull(session, "会话信息不能为空。");
    }

    @Override
    public String toString() {
        return "SessionPrincipal[userId=" + userId + ", role=" + role + ", session=<REDACTED>]";
    }
}
