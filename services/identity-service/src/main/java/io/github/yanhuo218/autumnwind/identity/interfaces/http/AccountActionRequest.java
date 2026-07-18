package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountActionRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$")
        String reasonCode
) {
}
