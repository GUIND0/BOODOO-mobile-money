# Audit Service

Audit Service will own traceable records of sensitive actions, administrative approvals, access events, and business decisions.

This PR initializes the application only. It does not implement audit schemas, ingestion APIs, append-only enforcement, search/export APIs, and event transport.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/audit-service`:

```sh
export AUDIT_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8092`; PostgreSQL 17 is exposed on `127.0.0.1:5443`. These defaults avoid the other BOODOO service ports.

Configuration overrides: `AUDIT_DB_URL`, `AUDIT_DB_USER`, `AUDIT_DB_PASSWORD`, and `AUDIT_SERVER_PORT`. Compose provisions the fixed database/user names `audit_db`/`audit_user`; URL and user overrides target an independently provisioned database. Never commit real credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

```sh
curl --fail http://localhost:8092/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and reject unauthenticated requests outside health. They require neither the development database nor the development password. They fail if Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Own audit-event ingestion, event identifiers, retention metadata, and authorized search/export; accounting journals remain in Ledger Service.
- Define a versioned event envelope with actor, action, target reference, outcome, occurrence/receipt times, source identity, and correlation identifiers.
- Authenticate producers and derive their source identity from trusted credentials rather than trusting arbitrary actor or source fields in a submitted event.
- Make event ingestion idempotent by producer and event identifier. Detect conflicting duplicates and account for delayed or out-of-order events.
- Preserve accepted records through append-only storage controls and a defined tamper-evidence mechanism; record corrections as linked new events.
- Exclude passwords, PINs, OTPs, tokens, signing keys, and unnecessary personal data from audit payloads. Apply access restrictions and approved retention rules.
- Define durable delivery, acknowledgement, and failure handling with producers. Sensitive actions must not silently lose their audit trail.
- Audit access to audit searches and exports, and separate event-reader permissions from administration and retention-management permissions.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates schemas rather than creating them.
- Keep other services out of this database; define authenticated APIs or versioned events for integration.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
