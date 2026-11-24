# Risk Compliance Service — local policy evaluations

Set RISK_POLICY_VERSION, RISK_AUTOMATIC_AMOUNT (positive integer XOF), RISK_EVALUATION_TTL_SECONDS (10–600), and optionally RISK_BLOCKED_CUSTOMERS (comma-separated customer UUIDs). These are operator-provisioned local policy inputs, not legal thresholds. No automatic amount policy is supplied by default. Empty blocked-customers explicitly means no configured local blocks. Bump the version on changes; a deterministic rules fingerprint captures the exact threshold, TTL and configured blocked IDs.

POST /internal/v1/evaluations requires risk:evaluations:write and a service identity. Body contains id (assessment UUID/idempotency key), reference (business transaction UUID), customerId, positive amount and XOF currency. Configured blocked customer -> DENY; above automatic amount -> REVIEW; otherwise ALLOW under these local rules only. Exact producer-bound assessment retries replay the original immutable decision, even after expiry or a configuration change; a new assessment requires a new ID. Multiple assessments can refer to one transaction. GET /internal/v1/evaluations/{id} requires risk:evaluations:read.

No screening provider, sanctions list, legal eligibility assessment, manual override, KYC validation or financial posting is included. ALLOW is not a certification or a reservation. Execution must validate transaction/customer/amount, expiry, authoritative KYC/wallet facts and local policy. A stale assessment never renews by retry; unavailable or REVIEW/DENY decisions must not be treated as approval. Operator configuration changes require deployment controls and audit outside this initial API.

## Run and verify

Set `RISK_COMPLIANCE_DB_PASSWORD`, `RISK_COMPLIANCE_JWT_ISSUER_URI` and `RISK_COMPLIANCE_JWT_JWK_SET_URI`, then run `docker compose up -d --wait` and `./mvnw spring-boot:run` from this service. Existing DB/API ports are retained. Flyway owns the schema; JDBC replaces the unused JPA initialization. `./mvnw -o clean verify` exercises real PostgreSQL 17 and ephemeral signed JWTs.

Tokens require the configured issuer, expiry, audience `boodoo-risk-compliance-api`, signed integrator_id, signed actor_type service and a stable subject. Scopes are checked independently. Tenant/actor headers cannot override claims. Use HTTPS in deployed traffic. Invalid requests return 400, authentication 401, authorization 403, unknown/foreign resources 404, state/idempotency conflicts 409, and storage unavailability 503 with sanitized problem details. No end-user OTP is sent or verified.
