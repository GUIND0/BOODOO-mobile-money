# Transfer Service

Transfer Service will own transfer requests, their lifecycle, idempotency, and orchestration of wallet and ledger operations in BOODOO.

This initial setup provides the application bootstrap, PostgreSQL configuration, Flyway integration, health endpoint, and a PostgreSQL-backed startup test. Transfer APIs and money movement are not implemented yet.

## Requirements

- JDK 25 or later
- Docker with a running daemon (also required for tests)
- Maven is downloaded by the included wrapper

## Run locally

From `services/transfer-service`:

```sh
export TRANSFER_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8082`; PostgreSQL is exposed on `127.0.0.1:5433`. This avoids the Wallet Service defaults of `8081` and `5432`.

```sh
curl --fail http://localhost:8082/actuator/health
```

Configuration can be overridden with `TRANSFER_DB_URL`, `TRANSFER_DB_USER`, `TRANSFER_DB_PASSWORD`, and `TRANSFER_SERVER_PORT`. The Compose database uses the fixed database/user names `transfer_db`/`transfer_user`; URL and user overrides are for an independently provisioned database. Do not commit actual credentials.

Stop the development database with `docker compose down`. Its named volume retains data.

## Verify

```sh
./mvnw verify
```

The test starts an isolated PostgreSQL 17 container, checks database connectivity, and requests the application's health endpoint on a random port. It does not require the development database or `TRANSFER_DB_PASSWORD`. Tests fail when Docker is unavailable rather than silently skipping database verification.

## Service boundary and next steps

- Own transfer identifiers, request fingerprints, idempotency records, processing status, and transfer history.
- Access wallet eligibility through Wallet Service contracts and accounting through Ledger Service contracts; never write directly into their databases.
- Define the ledger posting and retry contracts before exposing a transfer execution API. A timeout must remain an uncertain outcome until checked, and must not trigger a second posting.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates the schema instead of creating it.
- Add durable request idempotency, atomic double-entry ledger posting, recovery/reconciliation, authentication, and authorization before enabling money movement.

Kafka, transfer persistence, and wallet/ledger clients will be added when their contracts are defined.
