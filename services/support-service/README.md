# Support Service

Support Service will own customer complaints, transaction disputes, investigation cases, and support follow-up.

This PR initializes the application only. It does not implement case schemas, customer/operator APIs, attachments, assignment workflows, and investigation integrations.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/support-service`:

```sh
export SUPPORT_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8093`; PostgreSQL 17 is exposed on `127.0.0.1:5444`. These defaults avoid the other BOODOO service ports.

Configuration overrides: `SUPPORT_DB_URL`, `SUPPORT_DB_USER`, `SUPPORT_DB_PASSWORD`, and `SUPPORT_SERVER_PORT`. Compose provisions the fixed database/user names `support_db`/`support_user`; URL and user overrides target an independently provisioned database. Never commit real credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

```sh
curl --fail http://localhost:8093/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and reject unauthenticated requests outside health. They require neither the development database nor the development password. They fail if Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Own case references, category, status, assignment, priority, notes, evidence references, and resolution history.
- Link cases to customer and transaction identifiers through authorized contracts; customer profiles and financial records remain in their owning services.
- Define customer case submission, operator triage, assignment, escalation, resolution, and reopening with explicit permissions and audit events.
- Enforce case ownership for customer access and least-privilege access for support staff. Protect attachments and redact sensitive financial and identity information.
- Make case creation idempotent and preserve immutable decision history rather than overwriting earlier investigation outcomes.
- Define response targets, deadlines, and notification requests through Notification Service without coupling case state to delivery success.
- Route approved reversals, wallet restrictions, and account recovery to their owning workflows. Support staff access alone must not move funds or reset credentials.
- Apply retention and controlled evidence export; do not expose OTPs, PINs, tokens, or internal-only investigation details in customer responses.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates schemas rather than creating them.
- Keep other services out of this database; define authenticated APIs or versioned events for integration.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
