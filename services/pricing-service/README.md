# Pricing Service — immutable transfer quotes

Quotes use an explicitly configured policy. Set PRICING_POLICY_VERSION, PRICING_BASIS_POINTS (0–10000), PRICING_FLAT_FEE (nonnegative integer XOF) and PRICING_QUOTE_TTL_SECONDS (10–3600). No production tariff is assumed. Increment the version whenever parameters change; response snapshots also contain the exact parameters. Fee = ceil(amount × basisPoints / 10000) + flatFee, paid by the sender in addition to principal. Overflow is rejected. This initial P2P quote has no commissions or currency conversion.

POST /internal/v1/quotes requires pricing:quotes:write and an actor-bound Idempotency-Key. Body: reference UUID, positive integer amount and currency XOF. Reference and key are unique per tenant. 201 includes ID, reference, amount, fee, totalDebit, policyVersion, parameters, createdAt and expiresAt. Replays remain the original snapshot even after expiry; execution must reject expired quotes rather than treating replay as renewal. GET /internal/v1/quotes/{id} requires pricing:quotes:read. Responses are no-store; quotes are immutable. A quote neither reserves funds nor charges a fee. End-user consent and execution-time validation belong to transfer orchestration.

## Run and verify

Set `PRICING_DB_PASSWORD`, `PRICING_JWT_ISSUER_URI` and `PRICING_JWT_JWK_SET_URI`, then run `docker compose up -d --wait` and `./mvnw spring-boot:run` from this service. Existing DB/API ports are retained. Flyway owns the schema; JDBC replaces the unused JPA initialization. `./mvnw -o clean verify` exercises real PostgreSQL 17 and ephemeral signed JWTs.

Tokens require the configured issuer, expiry, audience `boodoo-pricing-api`, signed integrator_id, signed actor_type service and a stable subject. Scopes are checked independently. Tenant/actor headers cannot override claims. Use HTTPS in deployed traffic. Invalid requests return 400, authentication 401, authorization 403, unknown/foreign resources 404, state/idempotency conflicts 409, and storage unavailability 503 with sanitized problem details. No end-user OTP is sent or verified.
