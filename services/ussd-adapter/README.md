# USSD Adapter

USSD Adapter will own provider-specific USSD callback adaptation, menu-session state, and translation of customer choices into BOODOO application requests.

This PR initializes the application only. It does not implement session schemas, provider callbacks, menu flows, authentication integration, and financial-service clients.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/ussd-adapter`:

```sh
export USSD_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8094`; PostgreSQL 17 is exposed on `127.0.0.1:5445`. These defaults avoid the other BOODOO service ports.

Configuration overrides: `USSD_DB_URL`, `USSD_DB_USER`, `USSD_DB_PASSWORD`, and `USSD_SERVER_PORT`. Compose provisions the fixed database/user names `ussd_db`/`ussd_user`; URL and user overrides target an independently provisioned database. Never commit real credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

```sh
curl --fail http://localhost:8094/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and reject unauthenticated requests outside health. They require neither the development database nor the development password. They fail if Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Own transport-session identifiers, dialog/menu state, session expiry, and mapping between provider callbacks and application commands.
- Define the operator or aggregator callback contract, authenticated provider identity, signature or mutual-TLS validation, replay protection, and callback deadlines before accepting traffic.
- Treat an incoming phone number as provider-supplied context, not proof of account ownership. Delegate credential verification and sensitive-operation authorization to Identity Service.
- Handle duplicate, delayed, and out-of-order callbacks with versioned session transitions and stable operation identifiers.
- Never persist or log raw PINs, OTPs, or complete sensitive menu input; define redaction, short retention, and secure transient handling before adding interactive authentication.
- Show recipient, amount, currency, and fees before transaction confirmation. Delegate limits, pricing, risk, and financial posting to their owning contracts.
- Keep financial outcome separate from USSD session expiry: a disconnected menu does not establish that a payment failed. Use the same transaction reference when checking an uncertain outcome.
- Define localized menus, response-size limits, safe error messages, and test-provider fixtures without sending real telecom traffic.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates schemas rather than creating them.
- Keep other services out of this database; define authenticated APIs or versioned events for integration.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
