package io.github.yanhuo218.autumnwind.identity.domain.security;

public record IssuedToken(String rawValue, String hash) {

    @Override
    public String toString() {
        return "IssuedToken[rawValue=<REDACTED>, hash=<REDACTED>]";
    }
}
