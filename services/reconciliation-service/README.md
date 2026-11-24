# Reconciliation Service — frozen internal integrity checks

POST /internal/v1/reconciliations requires reconciliation:checks:write and Idempotency-Key. GET /internal/v1/reconciliations/{id} requires reconciliation:checks:read. Both require a service principal. The caller token is relayed to a configured Ledger Service origin, so it must also target the ledger audience and grant ledger:reconciliation:read. Configure RECONCILIATION_LEDGER_BASE_URL; redirects are disabled, responses are bounded to 64 KiB, and calls have bounded connect/request/overall waits. All dependency calls occur outside persistence transactions.

The workflow consumes the single-SQL-snapshot integrity contract from the balanced-journal PR. Nonnegative journal count, balanced-journal count and normal-balance validity are validated with a fresh ledger check timestamp (within 60 seconds past / 30 seconds future). Invalid, stale, oversized, unavailable or unauthorized responses return 503 and create no report. CONSISTENT means no internal journal/balance discrepancy in that observation; DISCREPANCY preserves the counts for investigation. This is not reconciliation with bank statements or proof of authorized business transactions.

Reports freeze ledgerCheckedAt and completedAt, are tenant scoped and database immutable. Keys bind to the service subject; exact retries replay the historical report without refreshing it. Concurrent duplicate checks may perform duplicate read-only ledger requests, but only one report is stored. No journal, payment or account is modified. External settlement reconciliation and scheduling/alert delivery remain subsequent work.

## Run and verify

Set `RECONCILIATION_DB_PASSWORD`, `RECONCILIATION_JWT_ISSUER_URI` and `RECONCILIATION_JWT_JWK_SET_URI`, then run `docker compose up -d --wait` and `./mvnw spring-boot:run` from this service. Existing DB/API ports are retained. Flyway owns the schema; JDBC replaces the unused JPA initialization. `./mvnw -o clean verify` exercises real PostgreSQL 17 and ephemeral signed JWTs.

Tokens require the configured issuer, expiry, audience `boodoo-reconciliation-api`, signed integrator_id, signed actor_type service and a stable subject. Scopes are checked independently. Tenant/actor headers cannot override claims. Use HTTPS in deployed traffic. Invalid requests return 400, authentication 401, authorization 403, unknown/foreign resources 404, state/idempotency conflicts 409, and storage unavailability 503 with sanitized problem details. No end-user OTP is sent or verified.
