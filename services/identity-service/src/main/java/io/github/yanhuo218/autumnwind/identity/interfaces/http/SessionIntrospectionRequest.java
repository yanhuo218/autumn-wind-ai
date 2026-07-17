package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SessionIntrospectionRequest(
        @NotBlank @Size(max = 1024) String sessionValue
) {

    @Override
    public String toString() {
        return "SessionIntrospectionRequest[sessionValue=<REDACTED>]";
    }
}
