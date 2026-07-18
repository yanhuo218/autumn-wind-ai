package io.github.yanhuo218.autumnwind.modelregistry.application.model;

final class ModelCommandFields {

    private ModelCommandFields() {
    }

    static String normalizeProviderModelId(String value) {
        return normalize(value, 255, "服务商模型标识不能为空或超过 255 个字符。");
    }

    static String normalizeDisplayName(String value) {
        return normalize(value, 100, "模型显示名称不能为空或超过 100 个字符。");
    }

    private static String normalize(String value, int maximumCodePoints, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        String normalized = value.strip();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > maximumCodePoints
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
