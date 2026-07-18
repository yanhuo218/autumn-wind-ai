package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

public record EndpointConnectionTestRequest(Long expectedVersion) {

    public long requiredVersion() {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("端点版本不能为空。");
        }
        return expectedVersion;
    }
}
