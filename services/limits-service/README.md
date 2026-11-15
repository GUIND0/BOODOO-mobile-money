# Limits Service

Limits Service will own wallet balance ceilings, per-operation limits, and daily/monthly transaction allowances.

This PR initializes the application only. It does not implement limit schemas, policy APIs, allowance reservations, and usage counters.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/limits-service`:

```sh
export LIMITS_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8089`; PostgreSQL 17 is exposed on `127.0.0.1:5440`. These defaults avoid the other BOODOO service ports.

Configuration overrides: `LIMITS_DB_URL`, `LIMITS_DB_USER`, `LIMITS_DB_PASSWORD`, and `LIMITS_SERVER_PORT`. Compose provisions the fixed database/user names `limits_db`/`limits_user`; URL and user overrides target an independently provisioned database. Never commit real credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

```sh
curl --fail http://localhost:8089/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and reject unauthenticated requests outside health. They require neither the development database nor the development password. They fail if Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Own versioned limit policies by customer verification tier, actor category, operation type, and currency; KYC decisions remain in Customer Service.
- Define per-transaction, wallet-balance, daily, and monthly limits with explicit time-zone and period-boundary rules.
- Define atomic allowance reservation, confirmation, release, and expiry so simultaneous requests cannot independently consume the same remaining allowance.
- Scope reservations to a stable transaction identifier; make retries idempotent and reject identifier reuse with different request details.
- Resolve uncertain payment outcomes before releasing reserved allowance. A timeout does not establish that a payment failed.
- Keep balance ownership and overdraft prevention in the financial ledger; limit checks cannot replace atomic financial constraints.
- Audit policy changes and overrides, define recovery and usage reconciliation, and fail safely when an authoritative limit decision is unavailable.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates schemas rather than creating them.
- Keep other services out of this database; define authenticated APIs or versioned events for integration.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
