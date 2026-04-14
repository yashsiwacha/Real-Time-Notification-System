package com.epam.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NotificationSystemIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("notifications")
            .withUsername("notifications")
            .withPassword("notifications");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.security.api-key", () -> "test-api-key");
    }

    @Test
    void shouldRejectRequestWithoutApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(Map.of(
                "userId", "user-1",
                "type", "ORDER_STATUS",
                "message", "Order picked"
        ), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/notifications",
                HttpMethod.POST,
                requestEntity,
                String.class
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnDuplicateForSameIdempotencyKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-KEY", "test-api-key");

        Map<String, Object> payload = Map.of(
                "userId", "user-42",
                "type", "ORDER_STATUS",
                "message", "Out for delivery",
                "idempotencyKey", "order-42-out-for-delivery",
                "metadata", Map.of("orderId", "42")
        );

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

        ResponseEntity<Map> first = restTemplate.postForEntity("/api/notifications", requestEntity, Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity("/api/notifications", requestEntity, Map.class);

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode());
        assertFalse(Boolean.TRUE.equals(first.getBody().get("duplicate")));
        assertTrue(Boolean.TRUE.equals(second.getBody().get("duplicate")));
    }

    @Test
    void shouldRejectMissingIdempotencyKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-KEY", "test-api-key");

        Map<String, Object> payload = Map.of(
                "userId", "user-42",
                "type", "ORDER_STATUS",
                "message", "Out for delivery"
        );

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/notifications", requestEntity, Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
