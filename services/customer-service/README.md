# Customer Service

Customer Service will own BOODOO customer profiles, onboarding progress, KYC review status, verification tiers, and customer lifecycle decisions.

This initial setup provides the application bootstrap, independent PostgreSQL configuration, Flyway integration, a health endpoint, and a PostgreSQL-backed startup test. Customer tables, registration APIs, document uploads, and KYC workflows are not implemented yet.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/customer-service`:

```sh
export CUSTOMER_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8084`; PostgreSQL is exposed on `127.0.0.1:5435`. These defaults avoid the existing Wallet, Transfer, and Ledger Service ports.

```sh
curl --fail http://localhost:8084/actuator/health
```

Configuration can be overridden with `CUSTOMER_DB_URL`, `CUSTOMER_DB_USER`, `CUSTOMER_DB_PASSWORD`, and `CUSTOMER_SERVER_PORT`. Compose provisions the fixed database/user names `customer_db`/`customer_user`; URL and user overrides target an independently provisioned database. Never commit actual credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

## Verify

```sh
./mvnw verify
```

The test starts an isolated PostgreSQL 17 container, checks database connectivity, and requests the health endpoint on a random application port. It needs neither the development database nor `CUSTOMER_DB_PASSWORD`. It fails when Docker is unavailable rather than silently skipping verification.

## Customer boundary and next implementation

- Own stable customer identifiers, profile information, contact verification status, onboarding state, and lifecycle status.
- Own KYC submissions, review decisions, verification tiers, and review history. Automated screening and policy checks must be distinguished from the final review decision.
- Keep login credentials, PINs, OTP generation, token issuance, and sessions in the identity/authentication boundary; reference identities through stable identifiers.
- Keep wallet balances, financial postings, and transfer execution outside this service.
- Introduce versioned schema migrations under `src/main/resources/db/migration` with the first customer model; Hibernate validates the schema instead of creating it.
- Define phone-number normalization, uniqueness, duplicate-registration handling, and controlled phone-number changes before implementing registration.
- Define authorization for customer self-service and operator reviews, including traceable status changes and approval rules.
- Store identity documents in protected storage with restricted access; expose references and review metadata rather than publicly accessible document URLs.
- Apply data minimization, retention rules, access auditing, and redaction of personal information in logs.
- Publish customer eligibility and verification changes through a defined contract; other services must not read or write this database directly.

No customer endpoints, identity-provider integrations, document storage, or screening-provider integrations are exposed in this initialization.
