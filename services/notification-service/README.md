# Notification Service

Notification Service will deliver BOODOO transactional messages: OTP messages requested by Identity Service, transaction confirmations and receipts, and security alerts. V1 starts with SMS delivery; additional channels can follow through provider adapters.

This initial setup provides the application bootstrap, independent PostgreSQL configuration, Flyway integration, a health endpoint, and a PostgreSQL-backed startup test. Notification tables, delivery APIs, provider adapters, queues, retries, and delivery callbacks are not implemented yet. This initialization sends no messages.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/notification-service`:

```sh
export NOTIFICATION_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8087`; PostgreSQL is exposed on `127.0.0.1:5438`. These defaults avoid ports assigned to existing and reserved BOODOO services.

```sh
curl --fail http://localhost:8087/actuator/health
```

Configuration can be overridden with `NOTIFICATION_DB_URL`, `NOTIFICATION_DB_USER`, `NOTIFICATION_DB_PASSWORD`, and `NOTIFICATION_SERVER_PORT`. Compose provisions the fixed database/user names `notification_db`/`notification_user`; URL and user overrides target an independently provisioned database. Never commit credentials, OTPs, message contents, or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

## Verify

```sh
./mvnw verify
```

The test starts an isolated PostgreSQL 17 container, checks database connectivity, and requests the health endpoint on a random application port. It needs neither the development database nor `NOTIFICATION_DB_PASSWORD`. It fails when Docker is unavailable rather than silently skipping verification. No SMS provider is contacted.

## Notification boundary and next implementation

- Own notification requests, templates and template versions, provider references, delivery attempts, and delivery status.
- Deliver OTPs supplied by Identity Service. OTP generation, verification, expiry, and authentication decisions remain in Identity Service.
- Accept transaction facts from the financial services and render receipts; never infer or change a transaction's financial outcome from notification delivery status.
- Define authenticated service-to-service submission with recipient validation, approved templates, channel policy, and request limits before exposing delivery APIs.
- Use a durable delivery workflow with caller-scoped idempotency keys and payload conflict checks. Define retry rules for provider timeouts and uncertain delivery outcomes; use provider idempotency or status lookup where supported.
- Distinguish provider acceptance from confirmed delivery. Authenticate provider callbacks and handle duplicate or out-of-order status updates safely.
- Apply bounded retries with backoff, expiration deadlines, and failed-message investigation. Never deliver expired OTP challenges after a long retry delay.
- Keep provider credentials in external configuration. Exclude message bodies, OTPs, and full recipient details from logs; protect any sensitive delivery payload and apply short retention or deletion rules.
- Introduce versioned schema migrations under `src/main/resources/db/migration` with the first delivery model; Hibernate validates the schema instead of creating it.
- Define transactional events or submission contracts without reading or writing other services' databases. A notification failure must not reverse a completed payment.

No provider account, real recipient, authentication integration, SMS transport, or message publishing is included in this initialization.
