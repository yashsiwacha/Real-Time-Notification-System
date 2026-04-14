package com.yash.notifications.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class ApiSessionTokenService {

    public static final String COOKIE_NAME = "NOTIF_SESSION";

    private final String signingSecret;
    private final long ttlSeconds;

    public ApiSessionTokenService(@Value("${app.security.session-secret:}") String sessionSecret,
                                  @Value("${app.security.api-key:}") String apiKey,
                                  @Value("${app.security.session-ttl-seconds:43200}") long ttlSeconds) {
        String effectiveSecret = sessionSecret == null || sessionSecret.isBlank() ? apiKey : sessionSecret;
        if (effectiveSecret == null || effectiveSecret.isBlank()) {
            effectiveSecret = "dev-session-secret";
        }
        this.signingSecret = effectiveSecret;
        this.ttlSeconds = ttlSeconds;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String createToken() {
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = Long.toString(expiresAt);
        String payloadB64 = base64Url(payload.getBytes(StandardCharsets.UTF_8));
        String signatureB64 = base64Url(hmac(payload));
        return payloadB64 + "." + signatureB64;
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return false;
        }

        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            long expiresAt = Long.parseLong(payload);
            if (Instant.now().getEpochSecond() > expiresAt) {
                return false;
            }

            byte[] expectedSig = hmac(payload);
            byte[] actualSig = Base64.getUrlDecoder().decode(parts[1]);
            return MessageDigest.isEqual(expectedSig, actualSig);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign session token", ex);
        }
    }

    private String base64Url(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }
}
