package io.github.yanhuo218.autumnwind.gateway.web;

public record GatewayErrorResponse(String code, String message, String correlationId) {
}
