package io.github.yanhuo218.autumnwind.gateway.identity;

import java.util.Objects;

public record SessionIntrospectionRequest(String sessionValue) {

    public SessionIntrospectionRequest {
        Objects.requireNonNull(sessionValue, "Session 值不能为空。");
    }

    @Override
    public String toString() {
        return "SessionIntrospectionRequest[sessionValue=<REDACTED>]";
    }
}
