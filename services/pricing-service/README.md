# Pricing Service

Pricing Service will own versioned fee schedules, base distribution-network commissions, and reproducible transaction price quotes.

This PR initializes the application only. It does not implement tariff schemas, quote APIs, commission calculations, and pricing approval workflows.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/pricing-service`:

```sh
export PRICING_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8088`; PostgreSQL 17 is exposed on `127.0.0.1:5439`. These defaults avoid the other BOODOO service ports.

Configuration overrides: `PRICING_DB_URL`, `PRICING_DB_USER`, `PRICING_DB_PASSWORD`, and `PRICING_SERVER_PORT`. Compose provisions the fixed database/user names `pricing_db`/`pricing_user`; URL and user overrides target an independently provisioned database. Never commit real credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

```sh
curl --fail http://localhost:8088/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and reject unauthenticated requests outside health. They require neither the development database nor the development password. They fail if Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Own fee schedules, commission schedules, effective dates, and immutable tariff versions for the V1 operator and currency.
- Define deterministic quotes with amount, currency, fees, commissions, rounding policy, applicable tariff version, and quote expiry; identify every charge payer and beneficiary.
- Use integer minor units or explicitly scaled decimals. Apply currency-specific precision and explicit rounding rules; never use floating-point money.
- Require permission checks and traceable approvals before activating tariff changes. Preserve the tariff snapshot applied to every transaction.
- Keep financial posting, charging, wallet balances, and commission payout execution in the financial workflows. A quote is not an accounting entry.
- Define idempotency, quote validation at execution time, and handling of expired quotes before exposing pricing APIs.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates schemas rather than creating them.
- Keep other services out of this database; define authenticated APIs or versioned events for integration.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
