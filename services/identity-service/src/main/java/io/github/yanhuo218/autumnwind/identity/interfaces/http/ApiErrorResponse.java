package io.github.yanhuo218.autumnwind.identity.interfaces.http;

import java.util.List;

public record ApiErrorResponse(
        String code,
        String message,
        String correlationId,
        List<FieldErrorView> fieldErrors
) {

    public ApiErrorResponse {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
}
