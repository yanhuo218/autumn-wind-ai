package io.github.yanhuo218.autumnwind.notification.domain.smtp;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record SmtpSettings(
        String host,
        int port,
        SmtpSecurityMode securityMode,
        String username,
        String fromAddress,
        String fromName
) {

    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern EMAIL_LOCAL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+");

    public SmtpSettings {
        host = normalizeHost(host);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SMTP Port 必须在 1 到 65535 之间。");
        }
        Objects.requireNonNull(securityMode, "SMTP 加密模式不能为空。");
        username = normalizeOptional(username, 320, "SMTP 用户名不能超过 320 个字符。");
        fromAddress = normalizeEmail(fromAddress);
        fromName = requireText(fromName, 200, "发件名称不能为空或超过 200 个字符。");
    }

    private static String normalizeHost(String value) {
        String normalized = requireText(value, 253, "SMTP Host 不能为空或超过 253 个字符。")
                .toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.contains(":")) {
            throw new IllegalArgumentException("SMTP Host 格式不合法。");
        }

        String[] labels = normalized.split("\\.", -1);
        for (String label : labels) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("SMTP Host 格式不合法。");
            }
        }
        if (normalized.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
            validateIpv4(labels);
        }
        return normalized;
    }

    private static void validateIpv4(String[] labels) {
        if (labels.length != 4) {
            throw new IllegalArgumentException("SMTP Host 格式不合法。");
        }
        for (String label : labels) {
            if (label.length() > 1 && label.startsWith("0")) {
                throw new IllegalArgumentException("SMTP Host 格式不合法。");
            }
            int octet = Integer.parseInt(label);
            if (octet > 255) {
                throw new IllegalArgumentException("SMTP Host 格式不合法。");
            }
        }
    }

    private static String normalizeEmail(String value) {
        String normalized = requireText(value, 320, "发件地址不能为空或超过 320 个字符。");
        int separator = normalized.indexOf('@');
        if (separator <= 0 || separator != normalized.lastIndexOf('@')) {
            throw new IllegalArgumentException("发件地址格式不合法。");
        }

        String localPart = normalized.substring(0, separator);
        String domain = normalized.substring(separator + 1);
        if (localPart.length() > 64 || localPart.startsWith(".") || localPart.endsWith(".")
                || localPart.contains("..") || !EMAIL_LOCAL.matcher(localPart).matches() || !domain.contains(".")) {
            throw new IllegalArgumentException("发件地址格式不合法。");
        }
        try {
            return localPart + "@" + normalizeHost(domain);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("发件地址格式不合法。", exception);
        }
    }

    private static String requireText(String value, int maximumLength, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maximumLength, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, maximumLength, message);
    }
}
