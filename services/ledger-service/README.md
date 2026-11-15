# Ledger Service

Ledger Service will own BOODOO's chart of accounts, immutable accounting journals, balanced double-entry postings, and authoritative accounting balances.

This PR initializes the service only: application bootstrap, independent PostgreSQL configuration, Flyway integration, health endpoint, and a PostgreSQL-backed startup test. Accounting tables, posting APIs, and financial operations are not implemented yet.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/ledger-service`:

```sh
export LEDGER_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8083`; PostgreSQL is exposed on `127.0.0.1:5434`. These defaults avoid the Wallet and Transfer Service ports.

```sh
curl --fail http://localhost:8083/actuator/health
```

Configuration can be overridden with `LEDGER_DB_URL`, `LEDGER_DB_USER`, `LEDGER_DB_PASSWORD`, and `LEDGER_SERVER_PORT`. Compose provisions the fixed database/user names `ledger_db`/`ledger_user`; URL and user overrides target an independently provisioned database. Never commit actual credentials.

Stop the development database with `docker compose down`. Its named volume retains data.

## Verify

```sh
./mvnw verify
```

The test starts an isolated PostgreSQL 17 container, checks database connectivity, and requests the health endpoint on a random application port. It needs neither the development database nor `LEDGER_DB_PASSWORD`. It fails when Docker is unavailable rather than silently skipping verification.

## Accounting boundary and next implementation

- Own accounts, journals, journal entries, and accounting balances. Wallet lifecycle and transfer orchestration remain outside this service.
- Introduce versioned schema migrations under `src/main/resources/db/migration` with the first accounting model; Hibernate validates the schema instead of creating it.
- Commit all entries in one balanced journal atomically, with debits equal to credits per currency.
- Use integer minor units or explicitly scaled decimal amounts, never floating-point money.
- Make posting idempotency durable and scoped to the calling client; reject reuse of a key with different posting details.
- Resolve uncertain posting outcomes by reference before retrying; never treat a timeout as proof that a posting failed.
- Preserve posted journals and correct them through linked reversal journals.
- Define authorization, account constraints, concurrency controls, and reconciliation queries before exposing financial operations.
- Other services must use the Ledger Service contract rather than writing to its database.

No wallet/transfer clients, authentication, or posting endpoints are exposed in this initialization.
