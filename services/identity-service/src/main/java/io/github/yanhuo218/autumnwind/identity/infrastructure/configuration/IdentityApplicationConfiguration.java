package io.github.yanhuo218.autumnwind.identity.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.identity.domain.security.PasswordHasher;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.Argon2PasswordHasher;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.SecureTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class IdentityApplicationConfiguration {

    @Bean
    PasswordHasher passwordHasher() {
        return new Argon2PasswordHasher();
    }

    @Bean
    SecureTokenService secureTokenService() {
        return new SecureTokenService();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
