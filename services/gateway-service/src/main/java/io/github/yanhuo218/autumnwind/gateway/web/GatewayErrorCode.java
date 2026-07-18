package io.github.yanhuo218.autumnwind.gateway.web;

public enum GatewayErrorCode {
    INVALID_SESSION("AW-GATEWAY-AUTH-0001"),
    ROUTE_NOT_ALLOWED("AW-GATEWAY-ROUTING-0001"),
    REQUEST_TOO_LARGE("AW-GATEWAY-VALIDATION-0001"),
    IDENTITY_UNAVAILABLE("AW-GATEWAY-DEPENDENCY-0001"),
    MODEL_REGISTRY_UNAVAILABLE("AW-GATEWAY-DEPENDENCY-0002"),
    DOWNSTREAM_PROTOCOL_ERROR("AW-GATEWAY-DEPENDENCY-0003"),
    INTERNAL_ERROR("AW-GATEWAY-INTERNAL-0001");

    private final String value;

    GatewayErrorCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
