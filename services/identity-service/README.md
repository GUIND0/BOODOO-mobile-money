# Identity Service

Identity Service will own authentication identities, phone-ownership challenges, PIN lifecycle, sessions, access tokens, and role/permission assignments for BOODOO customers, distribution-network users, and operators.

This initial setup provides the application bootstrap, independent PostgreSQL configuration, Flyway integration, Spring Security configuration, a health endpoint, and PostgreSQL-backed integration tests. Registration, OTP, PIN, login, token issuance, and account-recovery workflows are not implemented yet.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/identity-service`:

```sh
export IDENTITY_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8086`; PostgreSQL is exposed on `127.0.0.1:5437`. These defaults avoid the ports assigned to existing and reserved BOODOO services.

```sh
curl --fail http://localhost:8086/actuator/health
```

Configuration can be overridden with `IDENTITY_DB_URL`, `IDENTITY_DB_USER`, `IDENTITY_DB_PASSWORD`, and `IDENTITY_SERVER_PORT`. Compose provisions the fixed database/user names `identity_db`/`identity_user`; URL and user overrides target an independently provisioned database. Never commit credentials, signing keys, PINs, OTPs, or personal data.

Stop the development database with `docker compose down`. Its named volume retains data.

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and verify that other requests are rejected without creating sessions. They need neither the development database nor `IDENTITY_DB_PASSWORD`. Tests fail when Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, and its response hides component details. All other requests are denied. Form login, HTTP Basic, logout, request caching, and server-side session creation are disabled. CSRF protection retains Spring Security's default behavior. Default generated-user configuration is excluded, so the application does not create a development login account.

This policy intentionally exposes no login or token endpoint. Authentication mechanisms and explicit endpoint permissions must be introduced with the corresponding workflows.

## Identity boundary and next implementation

- Own stable authentication identity identifiers and links to customer, distribution-network, and operator records. Customer profiles and KYC decisions remain in Customer Service.
- Define phone normalization, ownership verification, duplicate registration, and controlled number-change flows.
- Make OTP challenges purpose-bound, short-lived, single-use, and protected by attempt limits and resend throttling. Deliver challenges through a defined notification-provider contract; never log their values.
- Define PIN hashing, change and recovery workflows, failed-attempt limits, lockouts, and secure verification before accepting credentials.
- Use established identity/security libraries or an identity-provider integration for token issuance and validation. Define issuer, audience, expiry, signing-key rotation, refresh-token rotation, revocation, and session policy explicitly.
- Define customer, distribution-network, and operator access permissions, privileged-action controls, and stronger verification for sensitive operations.
- Protect recovery, lost-phone, phone-number-change, and suspected SIM-swap workflows against unauthorized account takeover.
- Introduce versioned schema migrations under `src/main/resources/db/migration` with the first identity model; Hibernate validates the schema instead of creating it.
- Audit authentication and administrative events without storing secrets in logs. Keep wallet balances, ledger postings, and transfer execution outside this service.
- Other services must use identity contracts and validate access tokens rather than accessing this database directly.

No OTP provider, production identity provider, token keys, or user credentials are included in this initialization.
