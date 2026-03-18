# Real-Time Notification System (Production-Oriented Spring Boot)

This service implements Swiggy and Zomato style notification delivery with reliability controls and production patterns.

## Production Features

- Real-time WebSocket delivery using STOMP and user-specific destinations
- JWT authentication with role-based access control (ADMIN and USER)
- API key and login-once cookie session support for operator console
- Token-bucket rate limiting per user
- Idempotency protection to prevent duplicate notification creation
- Retry with exponential backoff and dead-letter transition
- Persistent delivery state in PostgreSQL via Spring Data JPA
- Kafka-based event pipeline (producer -> topic -> consumer)
- Redis-backed queue fallback mode for environments without Kafka
- Redis-backed distributed presence registry for WebSocket users
- Redis-backed distributed token-bucket limiter via Lua script
- Flyway migrations for schema versioning
- Crash recovery loader that restores pending notifications from DB at startup
- Request correlation IDs through X-Request-Id for traceability
- Prometheus metrics endpoint and Grafana dashboard integration
- k6 load-test script with staged ramp to 10k virtual users

## Stack

- Java 17
- Spring Boot 3
- Spring WebSocket
- Spring Security
- Spring Data JPA
- Spring Kafka
- Flyway
- PostgreSQL (prod profile) and H2 (default local profile)
- Redis
- Prometheus and Grafana
- k6 (for load testing)

## Delivery Lifecycle

1. Client sends POST /api/notifications with X-API-KEY.
2. Service enforces rate limit and checks idempotency key.
3. Notification is persisted with ENQUEUED state.
4. Event is published to Kafka topic notifications.created.
5. Kafka consumer attempts WebSocket delivery to /user/queue/notifications.
6. On failure, state moves to RETRY_SCHEDULED with next attempt timestamp.
7. On success, state becomes DELIVERED.
8. If retries exceed configured maximum, state becomes DEAD_LETTER.

## Run Local

1. Build and start locally:
   mvn spring-boot:run

2. Default local profile uses embedded H2 at data/notifications.

3. Redis is required for limiter and presence features.
4. Kafka mode can be enabled with KAFKA_ENABLED=true.

## Run Tests

1. Integration tests use Testcontainers (PostgreSQL + Redis):
  mvn test

## Run Production-Like with Docker Compose

1. Start stack:
   docker compose up --build

2. Services started:
- app on 8080
- postgres on 5432
- redis on 6379
- kafka on 9092
- prometheus on 9090
- grafana on 3000

## CI/CD (GitHub Actions)

Workflow file:

- `.github/workflows/ci-cd.yml`

Pipeline behavior:

1. On push and pull request to main/master:
  - Runs `mvn verify`
  - Executes tests (including Testcontainers integration test when Docker is available on runner)
  - Uploads built JAR as artifact

2. On push events:
  - Builds Docker image
  - Publishes image to GHCR as:
    - `ghcr.io/<owner>/notification-system:latest` (default branch)
    - branch tag
    - sha tag

Notes:

- Ensure GitHub Actions has package write permission (already configured in workflow).
- If your repository uses branch name `main`, workflow triggers automatically.

## API Security

- JWT Login endpoint: POST /api/auth/login
- Role rules:
  - ROLE_ADMIN can publish notifications
  - ROLE_USER and ROLE_ADMIN can read system stats
- API key and cookie session remain available for operator workflows.

## API Examples

POST /api/notifications

Headers:
- Content-Type: application/json
- Authorization: Bearer <jwt-token>

Payload:

{
  "userId": "user-42",
  "type": "ORDER_STATUS",
  "message": "Your order is out for delivery",
  "idempotencyKey": "order-8843-out-for-delivery",
  "metadata": {
    "orderId": "8843",
    "etaMinutes": "18"
  }
}

GET /api/notifications/system-stats

Returns:
- pendingQueue
- deadLetter
- delivered
- connectedUsers

POST /api/auth/login

Payload:

{
  "username": "admin",
  "password": "admin123"
}

Response contains JWT token and role.

## WebSocket

- Connect: ws://localhost:8080/ws?userId=user-42
- Subscribe: /user/queue/notifications

## Configuration

Main config:
- src/main/resources/application.yml

Prod overrides:
- src/main/resources/application-prod.yml

Important keys:
- notification.dispatcher.poll-size
- notification.dispatcher.max-attempts
- notification.dispatcher.base-backoff-ms
- notification.kafka.enabled
- notification.kafka.topic
- notification.rate-limit.capacity
- notification.rate-limit.refill-per-second
- app.security.api-key

## Observability Dashboard

1. Prometheus scrape config: monitoring/prometheus.yml
2. Grafana provisioning config: monitoring/grafana/provisioning/datasources/prometheus.yml
3. Metrics endpoint: /actuator/prometheus

Important metrics:

- notification_delivery_latency
- notification_retry_total
- notification_dead_letter_total
- notification_queue_pending_approx

## Load Testing

1. Script: load-tests/k6-notifications.js
2. Run:
  k6 run ./load-tests/k6-notifications.js --env BASE_URL=http://localhost:8080 --env ADMIN_USERNAME=admin --env ADMIN_PASSWORD=admin123
3. Track:
  - TPS/RPS
  - Average and p95 latency
  - Failure percentage

## Horizontal Scaling Path

Current code already uses shared Redis for queue scheduling, user presence, and rate limiting.

For full horizontal scale:

1. Upgrade from Redis sorted-set polling to Redis Streams consumer groups or Kafka partitions for higher throughput.
2. Add sticky sessions or external WebSocket broker for deterministic cross-instance fan-out.
3. Run multiple app instances behind load balancer.
4. Add dead-letter replay endpoint with operator controls.
5. Export metrics to Prometheus and set alerts on retry and dead-letter growth.
