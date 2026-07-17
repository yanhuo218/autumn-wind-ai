package io.github.yanhuo218.autumnwind.identity.domain.security;

public interface PasswordHasher {

    String hash(CharSequence password);

    boolean matches(CharSequence password, String encodedPassword);
}
