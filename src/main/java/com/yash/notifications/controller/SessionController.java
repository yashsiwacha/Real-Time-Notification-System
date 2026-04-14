package com.yash.notifications.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yash.notifications.security.ApiSessionTokenService;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final String API_KEY_HEADER = "X-API-KEY";

    private final String configuredApiKey;
    private final ApiSessionTokenService tokenService;

    public SessionController(@Value("${app.security.api-key:}") String configuredApiKey,
                             ApiSessionTokenService tokenService) {
        this.configuredApiKey = configuredApiKey;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestHeader(value = API_KEY_HEADER, required = false) String providedApiKey,
                                   HttpServletRequest request) {
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "config_error", "message", "API key auth is not configured"));
        }

        if (providedApiKey == null || providedApiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized", "message", "Missing API key"));
        }

        if (!configuredApiKey.equals(providedApiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized", "message", "Invalid API key"));
        }

        String token = tokenService.createToken();
        ResponseCookie cookie = ResponseCookie.from(ApiSessionTokenService.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(isSecureRequest(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(tokenService.ttlSeconds()))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of(
                        "authenticated", true,
                        "expiresInSeconds", tokenService.ttlSeconds(),
                        "mode", "cookie_session"
                ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        ResponseCookie expiredCookie = ResponseCookie.from(ApiSessionTokenService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(isSecureRequest(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(Map.of("authenticated", false));
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        String token = readCookie(request, ApiSessionTokenService.COOKIE_NAME);
        boolean authenticated = tokenService.isValid(token);
        return Map.of("authenticated", authenticated, "mode", "cookie_session");
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto);
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
