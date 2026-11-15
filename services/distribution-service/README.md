# Distribution Service

Distribution Service will own BOODOO agent profiles, outlet information, onboarding approvals, and agent lifecycle status. V1 focuses on basic agents supporting customer cash-in and cash-out.

This initial setup provides the application bootstrap, independent PostgreSQL configuration, Flyway integration, a health endpoint, and a PostgreSQL-backed startup test. Agent tables, onboarding APIs, and cash-operation workflows are not implemented yet.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/distribution-service`:

```sh
export DISTRIBUTION_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8085`; PostgreSQL is exposed on `127.0.0.1:5436`. These defaults avoid the ports assigned to the Wallet, Transfer, Ledger, and Customer Services.

```sh
curl --fail http://localhost:8085/actuator/health
```

Configuration can be overridden with `DISTRIBUTION_DB_URL`, `DISTRIBUTION_DB_USER`, `DISTRIBUTION_DB_PASSWORD`, and `DISTRIBUTION_SERVER_PORT`. Compose provisions the fixed database/user names `distribution_db`/`distribution_user`; URL and user overrides target an independently provisioned database. Never commit actual credentials or agent data.

Stop the development database with `docker compose down`. Its named volume retains data.

## Verify

```sh
./mvnw verify
```

The test starts an isolated PostgreSQL 17 container, checks database connectivity, and requests the health endpoint on a random application port. It needs neither the development database nor `DISTRIBUTION_DB_PASSWORD`. It fails when Docker is unavailable rather than silently skipping verification.

## Agent boundary and next implementation

- Own stable agent identifiers, outlet information, onboarding state, approval decisions, and active/suspended/closed status.
- Link agents to authenticated identities and wallet identifiers through explicit contracts; credentials, PINs, OTPs, and sessions remain in the identity/authentication boundary.
- Define agent registration, operator approval, suspension, and reactivation with traceable decisions and role-based access.
- Expose agent eligibility for assisted cash-in and cash-out; authoritative wallet balances and financial journals remain with Wallet and Ledger Services.
- Display agent float using the financial services' contracts. Do not maintain an independently spendable copy of the balance or directly credit wallets.
- Route float replenishment and cash operations through authorized, idempotent financial workflows; never treat an uncertain financial outcome as a failed posting.
- Define access controls, customer authorization, cash-operation limits, and reconciliation before enabling assisted money movement.
- Introduce versioned schema migrations under `src/main/resources/db/migration` with the first agent model; Hibernate validates the schema instead of creating it.
- Protect business documents and personal information, restrict review access, and redact sensitive data in logs.
- Other services must use the Distribution Service contract rather than accessing its database directly.

Distributor hierarchies, commission sharing, and advanced liquidity management belong to later releases. No agent endpoints, wallet clients, authentication integrations, or money movement are exposed in this initialization.
