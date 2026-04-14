package com.yash.notifications.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.yash.notifications.security.ApiKeyAuthFilter;
import com.yash.notifications.security.JwtAuthFilter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class SecurityConfig {

    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${app.security.allowed-origins:http://localhost:8080}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toList());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   ApiKeyAuthFilter apiKeyAuthFilter) throws Exception {
        http
            .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; style-src 'self' https://fonts.googleapis.com 'unsafe-inline'; font-src 'self' https://fonts.gstatic.com data:; connect-src 'self' ws: wss:; img-src 'self' data: https:"
                ))
                .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .frameOptions(frame -> frame.sameOrigin())
            )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/demo", "/demo.html", "/favicon.ico").permitAll()
                        .requestMatchers("/assets/**").permitAll()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/session/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/notifications").hasAnyRole("ADMIN", "USER")
                    .requestMatchers(HttpMethod.GET, "/api/notifications/system-stats").hasAnyRole("ADMIN", "USER")
                    .requestMatchers(HttpMethod.GET, "/api/notifications/overview").hasAnyRole("ADMIN", "USER")
                    .requestMatchers(HttpMethod.GET, "/api/notifications/recent-events").hasAnyRole("ADMIN", "USER")
                    .requestMatchers(HttpMethod.GET, "/api/notifications/failures").hasAnyRole("ADMIN", "USER")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-API-KEY", "X-Request-Id", "Authorization"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
