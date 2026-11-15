# Risk Compliance Service

Risk Compliance Service will own basic fraud checks, configured compliance rules, screening results, and review alerts.

This PR initializes the application only. It does not implement risk schemas, evaluation APIs, screening-provider clients, and investigation workflows.

## Requirements

- JDK 25 or later
- Docker with a running daemon, including for tests
- Maven is downloaded by the included wrapper

## Run locally

From `services/risk-compliance-service`:

```sh
export RISK_COMPLIANCE_DB_PASSWORD='choose-a-local-development-password'
docker compose up -d --wait
./mvnw spring-boot:run
```

The application listens on port `8090`; PostgreSQL 17 is exposed on `127.0.0.1:5441`. These defaults avoid the other BOODOO service ports.

Configuration overrides: `RISK_COMPLIANCE_DB_URL`, `RISK_COMPLIANCE_DB_USER`, `RISK_COMPLIANCE_DB_PASSWORD`, and `RISK_COMPLIANCE_SERVER_PORT`. Compose provisions the fixed database/user names `risk_compliance_db`/`risk_compliance_user`; URL and user overrides target an independently provisioned database. Never commit real credentials or customer data.

Stop the development database with `docker compose down`. Its named volume retains data.

```sh
curl --fail http://localhost:8090/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start an isolated PostgreSQL 17 container, check database connectivity and HTTP health, and reject unauthenticated requests outside health. They require neither the development database nor the development password. They fail if Docker is unavailable rather than silently skipping verification.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Own versioned risk policies, evaluation results, screening references, alerts, and investigation decisions.
- Keep customer KYC evidence and verification-tier ownership in Customer Service; consume the eligibility facts required by an evaluation.
- Define evaluation outcomes, reason codes, policy versions, decision expiry, and explicit behavior when screening dependencies are unavailable.
- Use approved country/operator rules and screening sources. Do not treat this initialization as regulatory certification or an automated final determination of customer eligibility.
- Make evaluations repeatable by request reference, record the policy and evidence references used, and define controlled reassessment when facts change.
- Define authorized case review, dual approval for sensitive overrides, escalation, and an audit trail for every decision.
- Minimize personal-data exposure in requests, logs, and review interfaces; configure retention and access controls before integrating real screening providers.
- Keep payment posting and ledger updates outside this service. A risk recommendation alone must not debit, credit, reverse, or freeze an account.
- Introduce versioned migrations under `src/main/resources/db/migration` with the first persistence model. Hibernate validates schemas rather than creating them.
- Keep other services out of this database; define authenticated APIs or versioned events for integration.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
