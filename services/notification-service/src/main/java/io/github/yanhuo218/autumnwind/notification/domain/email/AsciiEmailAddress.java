package io.github.yanhuo218.autumnwind.notification.domain.email;

import java.util.Locale;
import java.util.regex.Pattern;

public final class AsciiEmailAddress {

    private static final Pattern LOCAL_PART = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private AsciiEmailAddress() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("邮箱地址不能为空。");
        }
        String normalized = value.trim();
        if (normalized.length() > 320 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("邮箱地址格式不合法。");
        }

        int separator = normalized.indexOf('@');
        if (separator <= 0 || separator != normalized.lastIndexOf('@')) {
            throw new IllegalArgumentException("邮箱地址格式不合法。");
        }

        String localPart = normalized.substring(0, separator);
        String domain = normalized.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (localPart.length() > 64 || localPart.startsWith(".") || localPart.endsWith(".")
                || localPart.contains("..") || !LOCAL_PART.matcher(localPart).matches() || !domain.contains(".")) {
            throw new IllegalArgumentException("邮箱地址格式不合法。");
        }
        for (String label : domain.split("\\.", -1)) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("邮箱地址格式不合法。");
            }
        }
        return localPart + "@" + domain;
    }
}
