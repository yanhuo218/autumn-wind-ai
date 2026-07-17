package io.github.yanhuo218.autumnwind.identity.infrastructure.security;

import io.github.yanhuo218.autumnwind.identity.application.error.InvalidSessionException;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionService;
import io.github.yanhuo218.autumnwind.identity.application.session.SessionView;
import io.github.yanhuo218.autumnwind.identity.interfaces.http.SessionCookieFactory;
import io.github.yanhuo218.autumnwind.identity.interfaces.http.SessionPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class SessionCookieAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;

    public SessionCookieAuthenticationFilter(SessionService sessionService) {
        this.sessionService = Objects.requireNonNull(sessionService, "会话服务不能为空。");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String rawSessionToken = findUniqueSessionCookie(request.getCookies());
        if (rawSessionToken != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(rawSessionToken);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String rawSessionToken) {
        try {
            SessionView session = sessionService.currentSession(rawSessionToken);
            SessionPrincipal principal = new SessionPrincipal(
                    session.user().id(),
                    session.user().role(),
                    session
            );
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
            );
            var strategy = SecurityContextHolder.getContextHolderStrategy();
            var context = strategy.createEmptyContext();
            context.setAuthentication(authentication);
            strategy.setContext(context);
        }
        catch (InvalidSessionException exception) {
            // 无效会话按匿名请求继续，由后续授权规则决定是否拒绝。
        }
    }

    private static String findUniqueSessionCookie(Cookie[] cookies) {
        if (cookies == null) {
            return null;
        }
        String value = null;
        for (Cookie cookie : cookies) {
            if (!SessionCookieFactory.COOKIE_NAME.equals(cookie.getName())) {
                continue;
            }
            if (value != null || cookie.getValue() == null || cookie.getValue().isBlank()) {
                return null;
            }
            value = cookie.getValue();
        }
        return value;
    }
}
