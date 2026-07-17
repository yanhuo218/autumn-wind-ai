package io.github.yanhuo218.autumnwind.identity.application.session;

import java.util.Objects;

public record LoginResult(String rawSessionToken, SessionView session) {

    public LoginResult {
        Objects.requireNonNull(rawSessionToken, "原始会话 Token 不能为空。");
        Objects.requireNonNull(session, "会话信息不能为空。");
    }

    @Override
    public String toString() {
        return "LoginResult[rawSessionToken=<REDACTED>, session=<REDACTED>]";
    }
}
