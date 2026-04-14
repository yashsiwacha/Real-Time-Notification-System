package com.yash.notifications.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yash.notifications.security.JwtTokenService;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtTokenService jwtTokenService;
    private final String adminUsername;
    private final String adminPassword;
    private final String userUsername;
    private final String userPassword;

    public AuthController(JwtTokenService jwtTokenService,
                          @Value("${app.security.admin.username:admin}") String adminUsername,
                          @Value("${app.security.admin.password:admin123}") String adminPassword,
                          @Value("${app.security.user.username:user}") String userUsername,
                          @Value("${app.security.user.password:user123}") String userPassword) {
        this.jwtTokenService = jwtTokenService;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.userUsername = userUsername;
        this.userPassword = userPassword;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthLoginRequest request) {
        String role;
        if (adminUsername.equals(request.username()) && adminPassword.equals(request.password())) {
            role = "ADMIN";
        } else if (userUsername.equals(request.username()) && userPassword.equals(request.password())) {
            role = "USER";
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized", "message", "Invalid credentials"));
        }

        String token = jwtTokenService.generateToken(request.username(), role);
        return ResponseEntity.ok(Map.of(
                "tokenType", "Bearer",
                "token", token,
                "role", role,
                "username", request.username()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized", "message", "Missing or invalid token"));
        }
        return ResponseEntity.ok(Map.of("username", principal.getName()));
    }
}
