package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import java.util.Objects;

public final class ModelRegistryApplicationException extends RuntimeException {

    private final ModelRegistryErrorCode code;

    public ModelRegistryApplicationException(ModelRegistryErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "错误码不能为空。");
    }

    public ModelRegistryApplicationException(ModelRegistryErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "错误码不能为空。");
    }

    public ModelRegistryErrorCode code() {
        return code;
    }
}
