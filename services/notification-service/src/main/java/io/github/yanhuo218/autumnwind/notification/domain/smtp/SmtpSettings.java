package io.github.yanhuo218.autumnwind.notification.domain.smtp;

import io.github.yanhuo218.autumnwind.notification.domain.email.AsciiEmailAddress;

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

    public SmtpSettings {
        host = normalizeHost(host);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SMTP Port 必须在 1 到 65535 之间。");
        }
        Objects.requireNonNull(securityMode, "SMTP 加密模式不能为空。");
        username = normalizeOptional(username, 320, "SMTP 用户名不能超过 320 个字符。");
        fromAddress = AsciiEmailAddress.normalize(fromAddress);
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
