package io.github.yanhuo218.autumnwind.identity.domain.policy;

import java.net.IDN;
import java.util.Locale;

public final class EmailAddressNormalizer {

    private EmailAddressNormalizer() {
    }

    public static NormalizedEmail normalizeEmail(String rawEmail) {
        if (rawEmail == null) {
            throw new IllegalArgumentException("邮箱不能为空。");
        }

        String email = rawEmail.strip();
        int separator = email.lastIndexOf('@');
        if (separator <= 0 || separator == email.length() - 1 || email.indexOf('@') != separator) {
            throw new IllegalArgumentException("邮箱格式不正确。");
        }

        String localPart = email.substring(0, separator).toLowerCase(Locale.ROOT);
        if (localPart.length() > 64 || localPart.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("邮箱本地部分不合法。");
        }

        String domain = normalizeDomain(email.substring(separator + 1));
        String normalized = localPart + "@" + domain;
        if (normalized.length() > 320) {
            throw new IllegalArgumentException("邮箱长度超过限制。");
        }
        return new NormalizedEmail(normalized, domain);
    }

    public static String normalizeDomain(String rawDomain) {
        if (rawDomain == null) {
            throw new IllegalArgumentException("邮箱域名不能为空。");
        }

        String domain = rawDomain.strip();
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isBlank()) {
            throw new IllegalArgumentException("邮箱域名不能为空。");
        }

        try {
            String ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.length() > 253 || !ascii.contains(".")) {
                throw new IllegalArgumentException("邮箱域名不合法。");
            }
            return ascii;
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("邮箱域名不合法。", exception);
        }
    }
}
