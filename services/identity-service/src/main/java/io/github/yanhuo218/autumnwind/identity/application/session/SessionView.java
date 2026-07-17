package io.github.yanhuo218.autumnwind.identity.application.session;

import java.time.Instant;
import java.util.Objects;

public record SessionView(SessionUserView user, Instant createdAt, Instant expiresAt) {

    public SessionView {
        Objects.requireNonNull(user, "会话用户不能为空。");
        Objects.requireNonNull(createdAt, "会话创建时间不能为空。");
        Objects.requireNonNull(expiresAt, "会话过期时间不能为空。");
    }
}
