# Limits Service — atomic allowance reservations

Configure LIMITS_POLICY_VERSION, LIMITS_PER_TRANSACTION, LIMITS_DAILY, LIMITS_MONTHLY (positive integer XOF) and LIMITS_RESERVATION_TTL_SECONDS (10–3600). No legal/business ceilings are assumed. This initial policy covers outgoing P2P total debits by customer, not wallet balance ceilings. The caller must establish authoritative wallet/customer ownership; this service does not replace it.

POST /internal/v1/reservations requires limits:reservations:write, Idempotency-Key and body reference/customerId UUIDs, positive integer amount and currency XOF. Customer-scoped advisory locks atomically check per-transfer/daily/monthly usage. RESERVED and CONFIRMED count; RELEASED does not. Policy parameters and UTC authorization day/month are snapshotted. Limits apply to reservation time, not posting date. Response expiresAt is the earlier of configured TTL or UTC midnight. Execution must check it before posting; no automatic release occurs because expired or uncertain payments may already have posted.

GET /internal/v1/reservations/{id} requires limits:reservations:read. POST /{id}/confirm or /release requires limits:reservations:settle and the original service subject. Terminal transitions are idempotent, opposing transitions conflict. Confirm after expiry records a previously authorized outcome; it does not renew authorization. Release only after authoritative proof that the transfer did not post. Original POST retries always replay the original RESERVED snapshot; GET returns live settlement state. Reference and actor-bound key are tenant-unique. No balance is reserved by this API; overdraft protection remains in Ledger Service.

## Run and verify

Set `LIMITS_DB_PASSWORD`, `LIMITS_JWT_ISSUER_URI` and `LIMITS_JWT_JWK_SET_URI`, then run `docker compose up -d --wait` and `./mvnw spring-boot:run` from this service. Existing DB/API ports are retained. Flyway owns the schema; JDBC replaces the unused JPA initialization. `./mvnw -o clean verify` exercises real PostgreSQL 17 and ephemeral signed JWTs.

Tokens require the configured issuer, expiry, audience `boodoo-limits-api`, signed integrator_id, signed actor_type service and a stable subject. Scopes are checked independently. Tenant/actor headers cannot override claims. Use HTTPS in deployed traffic. Invalid requests return 400, authentication 401, authorization 403, unknown/foreign resources 404, state/idempotency conflicts 409, and storage unavailability 503 with sanitized problem details. No end-user OTP is sent or verified.
