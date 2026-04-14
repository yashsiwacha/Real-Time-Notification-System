# Real-Time Notification System

Production-oriented notification backend built with Spring Boot. The project delivers Swiggy/Zomato style real-time updates with reliability patterns such as idempotency, retry, dead-letter handling, rate limiting, persistence, observability, and load testing.

## What This Project Solves

This system is designed for event-driven notification delivery where clients must receive low-latency updates while backend workflows remain resilient under spikes.

Core use cases:
- Real-time order status updates
- At-least-once processing with controlled retries
- Duplicate request protection with idempotency
- Role-based secured publish and monitoring endpoints
- Production visibility through metrics and dashboards

## Features

- Real-time delivery using WebSocket + STOMP (`/user/queue/notifications`)
- JWT authentication with RBAC (`ADMIN`, `USER`)
- API key and session login support for operator workflows
- Token-bucket rate limiting (Redis + Lua)
- Idempotent notification creation per user
- Retry with exponential backoff and dead-letter transition
- Consumer-side idempotency guard for duplicate Kafka deliveries
- API backpressure and circuit-breaker protection during downstream instability
- Durable delivery state in PostgreSQL (Flyway + JPA)
- Kafka-first pipeline (toggleable) with Redis fallback mode
- Startup recovery of pending notifications
- Prometheus metrics and Grafana-ready configuration
- k6 load testing profile for sustained 500 events/sec validation

## Tech Stack

- Java 17
- Spring Boot 3.x
- Spring Web, WebSocket, Security, Data JPA
- Spring Kafka
- PostgreSQL
- Redis
- Flyway
- Prometheus + Grafana
- Docker Compose
- k6

## Architecture Overview

Request flow (Kafka mode):

1. Client calls `POST /api/notifications`.
2. Service validates auth, rate limit, and idempotency.
3. Notification is persisted as `ENQUEUED`.
4. Event is published to Kafka topic `notifications.created`.
5. Consumer attempts WebSocket delivery.
6. On success, state becomes `DELIVERED`.
7. On failure, state moves to `RETRY_SCHEDULED`.
8. After max attempts, state moves to `DEAD_LETTER`.

When Kafka is disabled, queueing/dispatch fallback runs through Redis-backed scheduling.

Partitioning and horizontal scaling:
- Producer sends with key = `userId` for per-user ordering.
- Kafka partitions enable parallel processing across consumers.
- Listener concurrency can be increased to process partitions in parallel in one instance.
- Multiple instances can join the same consumer group for horizontal scale.

Idempotency details:
- `idempotencyKey` is required on API requests.
- DB uniqueness on `(user_id, idempotency_key)` prevents race-condition duplicates.
- Consumer skips duplicate Kafka events when notification is already terminal (`DELIVERED` or `DEAD_LETTER`).

## Project Structure

- `src/main/java` application code
- `src/main/resources/application.yml` base config
- `src/main/resources/application-prod.yml` production config overrides
- `src/main/resources/db/migration` Flyway migrations
- `monitoring/` Prometheus and Grafana provisioning
- `load-tests/` k6 scripts and docs
- `docker-compose.yml` local production-like stack
- `render.yaml` Render deployment blueprint

## Quick Start (Local)

### 1. Prerequisites

- Java 17
- Maven
- Docker (recommended)

### 2. Run with Docker Compose (recommended)

```bash
docker compose up --build
```

Services:
- App: `http://localhost:8080`
- Postgres: `localhost:5432`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

### 3. Run without Docker

```bash
mvn spring-boot:run
```

Default profile uses local H2 for fast startup. Redis is still needed for limiter/presence features.

## Configuration

Key environment variables:

- `SPRING_PROFILES_ACTIVE` (use `prod` for PostgreSQL profile)
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_URL` or `REDIS_HOST`/`REDIS_PORT`
- `KAFKA_ENABLED` (`true` or `false`)
- `KAFKA_BOOTSTRAP_SERVERS`
- `API_KEY`
- `JWT_SECRET`
- `ALLOWED_ORIGINS`

Performance-related production knobs in `application-prod.yml`:
- Tomcat thread/connection limits
- Hikari pool sizing
- Kafka producer batching/compression
- Retry attempt/delay settings
- Rate limiter capacity/refill

## Security Model

### Authentication

- JWT login endpoint: `POST /api/auth/login`
- API key support for service/operator workflows
- Session-based operator login support

### Authorization

- `ROLE_ADMIN`: publish notifications
- `ROLE_ADMIN` and `ROLE_USER`: read system stats

## API Reference

### Login

`POST /api/auth/login`

Request:

```json
{
  "username": "admin",
  "password": "admin123"
}
```

### Create Notification

`POST /api/notifications`

Headers:
- `Authorization: Bearer <jwt>`
- `Content-Type: application/json`

Request:

```json
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
```

### System Stats

`GET /api/notifications/system-stats`

Response includes:
- `pendingQueue`
- `deadLetter`
- `delivered`
- `connectedUsers`

## WebSocket Usage

- Connect endpoint: `ws://localhost:8080/ws?userId=<userId>`
- Subscribe destination: `/user/queue/notifications`

## Observability

- Health: `/actuator/health`
- Metrics: `/actuator/prometheus`

Example business metrics:
- `notification_delivery_latency`
- `notification_retry_total`
- `notification_dead_letter_total`
- `notification_queue_pending_approx`
- `notification_duplicate_skipped_total`
- `notification_delivery_failed_total`
- `notification_backpressure_rejected_total`
- `notification_circuit_open_total`

Monitoring config:
- `monitoring/prometheus.yml`
- `monitoring/grafana/provisioning/datasources/prometheus.yml`
- `monitoring/grafana/provisioning/dashboards/dashboards.yml`

## Testing

Run tests:

```bash
mvn test
```

Integration tests use Testcontainers (Postgres + Redis) when available.

## Load Testing

k6 script: `load-tests/k6-notifications.js`

Default 500 EPS validation:

```bash
k6 run ./load-tests/k6-notifications.js \
  --env BASE_URL=http://localhost:8080 \
  --env ADMIN_USERNAME=admin \
  --env ADMIN_PASSWORD=admin123 \
  --env TARGET_EPS=500 \
  --env TEST_DURATION=3m
```

Auth-heavy baseline mode:

```bash
k6 run ./load-tests/k6-notifications.js \
  --env BASE_URL=http://localhost:8080 \
  --env ADMIN_USERNAME=admin \
  --env ADMIN_PASSWORD=admin123 \
  --env LOGIN_EACH_ITERATION=true
```

Troubleshooting summary (what broke and what was fixed):
- Duplicate creates under concurrent retries: fixed by DB uniqueness + producer race-safe duplicate resolution.
- Duplicate Kafka deliveries causing repeated sends: fixed by terminal-state idempotency check in consumer.
- High queue depth under burst load: fixed by API backpressure threshold (`429 backpressure_active`).
- Downstream dispatch instability causing retry storms: fixed by circuit breaker with deferred retries.

## Deployment

### Render (current cloud target)

- Blueprint file: `render.yaml`
- Runtime mode on Render currently uses `KAFKA_ENABLED=false`
- Requires external PostgreSQL (`DB_URL`, for example Neon)
- Includes Redis service in blueprint

Deployment guide:
- `deploy/render/RENDER_DEPLOYMENT.md`

### CI/CD

GitHub Actions workflow:
- `.github/workflows/ci-cd.yml`

Pipeline includes:
- Build and tests
- Docker image build
- Container image publish (GHCR)

## Current Status

- Production-oriented architecture implemented
- Render deployment path available and tested (Redis mode)
- Kafka mode available for environments with managed Kafka
- 10k load profile validated with major reliability improvements

## Roadmap

- External managed Kafka for cloud deployments
- Replay tooling for dead-letter notifications
- WebSocket fan-out optimization for multi-instance scale
- Further tail-latency reduction for strict p95 SLO targets
