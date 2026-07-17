package io.github.yanhuo218.autumnwind.identity.interfaces.http;

public record CsrfProtectionView(String headerName, String parameterName, String value) {

    @Override
    public String toString() {
        return "CsrfProtectionView[headerName=" + headerName
                + ", parameterName=" + parameterName
                + ", value=<REDACTED>]";
    }
}
