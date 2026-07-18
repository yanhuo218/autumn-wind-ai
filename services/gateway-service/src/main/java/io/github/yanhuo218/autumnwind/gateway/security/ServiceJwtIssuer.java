package io.github.yanhuo218.autumnwind.gateway.security;

public interface ServiceJwtIssuer {

    String issue(ServiceJwtRequest request);
}
