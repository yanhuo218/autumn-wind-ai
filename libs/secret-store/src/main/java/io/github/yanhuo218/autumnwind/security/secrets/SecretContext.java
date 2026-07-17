package io.github.yanhuo218.autumnwind.security.secrets;

import java.util.Objects;

/**
 * 将密文绑定到租户、用途和所有者，防止凭据在错误上下文中被复用。
 *
 * @param tenantId 不可变租户标识
 * @param purpose 凭据用途，例如模型端点或 SMTP
 * @param ownerId 凭据所有者标识
 */
public record SecretContext(String tenantId, String purpose, String ownerId) {

    private static final int MAX_COMPONENT_LENGTH = 128;

    public SecretContext {
        tenantId = requireComponent(tenantId, "tenantId");
        purpose = requireComponent(purpose, "purpose");
        ownerId = requireComponent(ownerId, "ownerId");
    }

    private static String requireComponent(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > MAX_COMPONENT_LENGTH) {
            throw new IllegalArgumentException(name + " 长度必须在 1 到 " + MAX_COMPONENT_LENGTH + " 之间");
        }
        return value;
    }
}
