# Audit Service — immutable service events

POST /internal/v1/events requires audit:events:write and a trusted service identity. Body contains id/resourceId UUIDs, type (WALLET_ACTIVATED, TRANSFER_POSTED, TRANSFER_REJECTED, LIMIT_RESERVED, KYC_APPROVED or POLICY_CHANGED), and a controlled uppercase reasonCode. Event ID is the idempotency key, scoped by tenant and bound to producer and canonical details. Exact retries return the original 201 event; conflicting reuse returns 409. The authenticated producer and server receipt timestamp are recorded. No arbitrary payload, PIN, contact details, evidence, user-entered message or client timestamp is accepted.

GET /internal/v1/events?resourceId=<uuid> requires audit:events:read. Optional limit 1–100 (default 50) and after UUID provide live ascending UUID pagination; nextAfter null means the current end. This is not chronological order or an export snapshot. Database triggers prohibit update/delete. Restrict database permissions and retention separately; these records are authenticated producer assertions, not cryptographic proof against a database administrator or independent proof that money moved. Consumers must consult the authoritative ledger for financial truth. Network delivery from other services remains their responsibility; retries must retain event ID.

## Run and verify

Set `AUDIT_DB_PASSWORD`, `AUDIT_JWT_ISSUER_URI` and `AUDIT_JWT_JWK_SET_URI`, then run `docker compose up -d --wait` and `./mvnw spring-boot:run` from this service. Existing DB/API ports are retained. Flyway owns the schema; JDBC replaces the unused JPA initialization. `./mvnw -o clean verify` exercises real PostgreSQL 17 and ephemeral signed JWTs.

Tokens require the configured issuer, expiry, audience `boodoo-audit-api`, signed integrator_id, signed actor_type service and a stable subject. Scopes are checked independently. Tenant/actor headers cannot override claims. Use HTTPS in deployed traffic. Invalid requests return 400, authentication 401, authorization 403, unknown/foreign resources 404, state/idempotency conflicts 409, and storage unavailability 503 with sanitized problem details. No end-user OTP is sent or verified.
