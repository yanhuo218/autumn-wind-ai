package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.configuration;

import java.time.Duration;
import java.util.Set;

public interface ServiceJwtValidationProperties {

    String issuer();

    String audience();

    Set<String> allowedCallers();

    Duration maximumLifetime();
}
