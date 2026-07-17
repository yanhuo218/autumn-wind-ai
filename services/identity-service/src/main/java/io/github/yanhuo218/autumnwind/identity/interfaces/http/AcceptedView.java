package io.github.yanhuo218.autumnwind.identity.interfaces.http;

public record AcceptedView(boolean accepted) {

    public static AcceptedView acceptedResponse() {
        return new AcceptedView(true);
    }
}
