# Reconciliation Service

Reconciliation Service will own comparison of transaction records, ledger journals, balances, and backing-fund statements to identify discrepancies.

This PR initializes the application only. It does not implement reconciliation schemas, source adapters, matching rules, schedules, and exception-review APIs.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/reconciliation-service`:

```sh
export RECONCILIATION_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8091`; PostgreSQL 17 is exposed on `127.0.0.1:5442`. These defaults avoid the other BOODOO service ports.

Configuration overrides: `RECONCILIATION_DB_URL`, `RECONCILIATION_DB_USER`, `RECONCILIATION_DB_PASSWORD`, and `RECONCILIATION_SERVER_PORT`. Compose provisions the fixed database/user names `reconciliation_db`/`reconciliation_user`; URL and user overrides target an independently provisioned database. Never commit real credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

```sh
curl --fail http://localhost:8091/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and reject unauthenticated requests outside health. They require neither the development database nor the development password. They fail if Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Own reconciliation runs, source manifests, matching results, discrepancies, and investigation status.
- Consume authorized, versioned exports, events, or APIs from financial services and statement providers; do not query or mutate their operational databases directly.
- Define stable cut-off times, currency boundaries, source completeness, and handling of delayed or duplicated records before declaring a run balanced.
- Compare transaction references, amounts, currencies, fees, reversals, and accounting totals using exact monetary representations.
- Make ingestion and run processing idempotent, retain evidence references, and support deterministic reruns without duplicating exceptions.
- Check internal accounting separately from backing-fund reconciliation; an internally balanced ledger does not establish that external statements agree.
- Route discrepancies through permissioned investigation and approval workflows. Never silently rewrite financial records or automatically create balancing entries.
- Request any approved financial correction through the authoritative posting workflow and reconcile the linked correction journal afterward.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates schemas rather than creating them.
- Keep other services out of this database; define authenticated APIs or versioned events for integration.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
