package com.epam.notifications.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.lang.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import jakarta.servlet.http.Cookie;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-KEY";

    private final String configuredApiKey;
    private final boolean requireApiKey;
    private final ApiSessionTokenService tokenService;

    public ApiKeyAuthFilter(@Value("${app.security.api-key:}") String configuredApiKey,
                            @Value("${app.security.require-api-key:false}") boolean requireApiKey,
                            ApiSessionTokenService tokenService) {
        this.configuredApiKey = configuredApiKey;
        this.requireApiKey = requireApiKey;
        this.tokenService = tokenService;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (requireApiKey && (configuredApiKey == null || configuredApiKey.isBlank())) {
            throw new IllegalStateException("app.security.require-api-key=true but app.security.api-key is empty");
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/")
                || path.equals("/demo")
                || path.equals("/demo.html")
                || path.startsWith("/api/auth")
                || path.startsWith("/api/session")
                || path.equals("/favicon.ico")
                || path.startsWith("/assets/")
                || path.startsWith("/actuator")
                || path.startsWith("/ws");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        if (method != null && HttpMethod.OPTIONS.matches(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!requireApiKey && (configuredApiKey == null || configuredApiKey.isBlank())) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedApiKey = request.getHeader(API_KEY_HEADER);
        String sessionToken = readCookie(request, ApiSessionTokenService.COOKIE_NAME);

        boolean validHeaderKey = providedApiKey != null && !providedApiKey.isBlank() && configuredApiKey.equals(providedApiKey);
        boolean validSessionCookie = tokenService.isValid(sessionToken);

        if (!validHeaderKey && !validSessionCookie) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            String message = (providedApiKey == null || providedApiKey.isBlank()) && (sessionToken == null || sessionToken.isBlank())
                    ? "Missing API key or session"
                    : "Invalid API key or session";
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
            return;
        }

        UsernamePasswordAuthenticationToken authenticationToken =
            new UsernamePasswordAuthenticationToken(
                validSessionCookie ? "ui-session-client" : "api-key-client",
                null,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")
            );
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        filterChain.doFilter(request, response);
    }

    private String readCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }
}
