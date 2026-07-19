package io.github.yanhuo218.autumnwind.inference.interfaces.http;

public record ApiErrorResponse(String code, String message, String correlationId) {
}
