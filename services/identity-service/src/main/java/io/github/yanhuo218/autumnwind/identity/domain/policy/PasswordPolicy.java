package io.github.yanhuo218.autumnwind.identity.domain.policy;

public record PasswordPolicy(int minimumLength, int maximumLength) {

    public PasswordPolicy {
        if (minimumLength < 12 || maximumLength < minimumLength || maximumLength > 1024) {
            throw new IllegalArgumentException("密码长度策略不合法。");
        }
    }

    public boolean accepts(CharSequence password) {
        if (password == null) {
            return false;
        }
        long length = password.codePoints().count();
        return length >= minimumLength && length <= maximumLength;
    }
}
