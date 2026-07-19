package io.github.yanhuo218.autumnwind.inference.security;

public interface ServiceJwtIssuer {

    String issue(ServiceJwtRequest request);
}
