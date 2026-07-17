package io.github.yanhuo218.autumnwind.identity.infrastructure.configuration;

import io.github.yanhuo218.autumnwind.identity.application.error.IdentityErrorCode;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionService;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.IdentitySecurityErrorWriter;
import io.github.yanhuo218.autumnwind.identity.infrastructure.security.SessionCookieAuthenticationFilter;
import io.github.yanhuo218.autumnwind.identity.interfaces.http.IdentityAuthController;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

@Configuration
public class BrowserSecurityConfiguration {

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName("AW_CSRF");
        repository.setHeaderName(IdentityAuthController.CSRF_HEADER_NAME);
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax"));
        return repository;
    }

    @Bean
    @Order(2)
    SecurityFilterChain browserSecurityFilterChain(
            HttpSecurity http,
            SessionService sessionService,
            CookieCsrfTokenRepository csrfTokenRepository,
            IdentitySecurityErrorWriter errorWriter
    ) throws Exception {
        SessionCookieAuthenticationFilter sessionFilter = new SessionCookieAuthenticationFilter(sessionService);

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                                request,
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                IdentityErrorCode.INVALID_SESSION,
                                "会话无效或已过期。"
                        ))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                request,
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                IdentityErrorCode.ACCESS_DENIED,
                                "当前请求不允许执行该操作。"
                        )))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/registration-options")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/registrations", "/api/v1/auth/sessions")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/session").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(sessionFilter, AnonymousAuthenticationFilter.class);

        return http.build();
    }
}
