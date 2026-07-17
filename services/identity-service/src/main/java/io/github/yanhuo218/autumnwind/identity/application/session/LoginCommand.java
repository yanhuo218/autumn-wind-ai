package io.github.yanhuo218.autumnwind.identity.application.session;

public record LoginCommand(String email, String password) {

    @Override
    public String toString() {
        return "LoginCommand[email=<REDACTED>, password=<REDACTED>]";
    }
}
