# Ledger Service — accounting accounts

Ledger Service owns an immutable, tenant-scoped chart of XOF accounts and balanced accounting journals. Account creation alone does not post entries or bind/activate a wallet. Posting is a separate privileged service operation. Wallet lifecycle and transfer orchestration remain outside this service.

## Run locally

Requires JDK 25+, Docker and a configured trusted token issuer. From `services/ledger-service`:

```sh
export LEDGER_DB_PASSWORD='choose-a-local-development-password'
export LEDGER_JWT_ISSUER_URI='https://your-authorization-server.example'
export LEDGER_JWT_JWK_SET_URI='https://your-authorization-server.example/jwks'
docker compose up -d --wait
./mvnw spring-boot:run
```

The API listens on port 8083; PostgreSQL 17 is exposed on `127.0.0.1:5434`, database `ledger_db`, user `ledger_user`. Override `LEDGER_DB_URL`, `LEDGER_DB_USER`, `LEDGER_DB_PASSWORD` and `LEDGER_SERVER_PORT` for other environments. Flyway owns the schema; JDBC replaces the initialization's unused JPA dependency/configuration. The migration creates account metadata and immutable idempotency snapshots only. `docker compose down` retains the database volume. Health remains available at `/actuator/health` without authentication, with details hidden.

## Service authorization

Both account endpoints require a signed RS256 JWT from the configured issuer, expiry, audience `boodoo-ledger-api` (override `LEDGER_JWT_AUDIENCE`), a trusted `integrator_id` matching `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`, signed `actor_type: service` and a stable nonblank subject of at most 128 characters. Issuer provisioning must restrict these scopes/claims to authorized service principals. The caller cannot override tenant or audit actor through headers/body fields. Deployed traffic must use HTTPS.

Creation requires `ledger:accounts:write`; reading requires `ledger:accounts:read`. Neither scope grants the other. This API does not authenticate end users or verify customer ownership: it manages an authorized service's chart of accounts, rather than creating or activating customer wallets. Future wallet/account binding must verify its own resource relationships.

## Create and read an account

```sh
curl --request POST 'http://127.0.0.1:8083/internal/v1/accounts' \
  --header "Authorization: Bearer $SERVICE_TOKEN" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: settlement-cash-001' \
  --data '{"code":"SETTLEMENT_CASH","currency":"XOF","type":"ASSET"}'
```

`POST /internal/v1/accounts` returns 201 with `id`, `code`, `currency`, `type`, `normalBalance`, `createdAt`, a relative `Location`, no-store caching and `Idempotency-Replayed`. `GET /internal/v1/accounts/{accountId}` returns that metadata after tenant filtering. Missing and foreign IDs both return 404 ACCOUNT_NOT_FOUND.

Codes are opaque identifiers, not personal names or document numbers: `[A-Z][A-Z0-9_-]{0,63}`. Code/currency are trimmed and uppercased. Only XOF is supported. Type must be ASSET, LIABILITY, EQUITY, INCOME or EXPENSE. `normalBalance` is a **side**, not an amount: ASSET/EXPENSE derive DEBIT; LIABILITY/EQUITY/INCOME derive CREDIT. Clients cannot supply it or a balance. Classification and metadata are immutable, enforced by database triggers. Account metadata has no update/delete or opening-balance endpoint. Journal posting is a separate permission and operation, described below.

One code/currency pair is unique per integrator. The creating service subject is recorded in the database, without exposing it in the response. Account identifiers are not automatically linked to wallets or Customer Service records.

## Retries and integrity

`Idempotency-Key` must match `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. A same-key retry with the same normalized code/currency/type and service subject returns the immutable original response. Different account details or a different subject conflict. Keys are tenant-scoped and have no automatic expiry. A different key for an existing code/currency returns 409 ACCOUNT_ALREADY_EXISTS, even if its requested classification differs; the existing account is never changed.

Per-key PostgreSQL advisory locks serialize retries. Unique constraints protect against concurrent distinct keys. Account and request snapshot commit in one transaction, so a storage failure leaves neither behind. Retry uncertain outcomes with the original key. Database constraints enforce the derived normal side and supported currency independently of request validation. Restricted database permissions remain necessary for persisted audit records.

Errors use problem details with stable application codes: 400 INVALID_REQUEST, 403 SERVICE_REQUIRED/ACTOR_REQUIRED, 404 ACCOUNT_NOT_FOUND, 409 IDEMPOTENCY_CONFLICT/ACCOUNT_ALREADY_EXISTS and 503 ACCOUNT_OPERATION_UNAVAILABLE. Authentication failures return 401, insufficient endpoint scopes 403, and unsupported content type 415. Responses do not echo SQL diagnostics or rejected body values. Apply token/payload redaction to ingress and monitoring.

## Balanced journals and balances

`POST /internal/v1/journals` requires a service identity and `ledger:journals:write`, plus Idempotency-Key. Body: `{"reference":"<transaction-uuid>","entries":[{"accountId":"<asset-uuid>","side":"DEBIT","amount":100},{"accountId":"<liability-uuid>","side":"CREDIT","amount":100}]}`. XOF amounts are integer minor units (one franc); 2–20 distinct owned accounts are required. Totals must balance and fit signed 64-bit integers. The actor-bound key fingerprints canonical account order. Replays return the original 201 receipt; different details/key reuse conflict, and a reference can be posted only once per tenant.

Account locks are acquired in UUID order. Derived normal balances cannot become negative or overflow, including simultaneous debits. This deliberately conservative V1 constraint applies to all account classes. Posted journals, entries and request snapshots are immutable. Deferred PostgreSQL constraint triggers independently reject unbalanced/incomplete journals and unsupported account balances at commit. Funding/settlement authority is a privileged service responsibility; this generic accounting endpoint must never be exposed as an unrestricted customer funding API.

`GET /internal/v1/journals/{id}` and `/internal/v1/journals/by-reference?reference=<uuid>` require `ledger:journals:read`; use reference lookup or the original idempotency key after uncertain network outcomes. `GET /internal/v1/accounts/{id}/balance` requires `ledger:balances:read` and returns the current authoritative amount derived from immutable entries, with no-store caching. There is no mutable balance projection or opening-balance field. Missing/foreign resources are indistinguishable. SQL or commit failures roll back all entries and snapshots. Reversals must be new linked compensating journals; no automatic reversal endpoint exists yet.

`GET /internal/v1/reconciliation/snapshot` requires `ledger:reconciliation:read` and a service identity. One SQL snapshot reports tenant journal count, unbalanced-journal count, invalid-normal-balance count and checkedAt. This checks internal accounting integrity only, not external bank settlement or whether a business transaction was authorized. No wallet, transfer or payment is activated by these APIs.

## Verification and next work

Run `./mvnw -o clean verify` with Docker. Tests use real PostgreSQL 17 and ephemeral signed JWTs. They cover classifications, service-only authorization, tenant isolation, canonical replay, actor-bound keys, concurrent creation, uniqueness, database immutability and rollback/retry after an injected persistence failure.

Next accounting work: linked reversal workflows, posting recovery/orchestration, external settlement reconciliation and controlled wallet/account binding. Wallet activation and transfer orchestration remain separate controlled workflows. No financial operation is implemented by this account API.

### Wallet accounting restrictions
Wallet control: POST `/internal/v1/wallets/{id}/control`, operator JWT with `wallets:control`, `ledger:controls:write` and ledger audience. Body: `{"id":"event UUID","state":"FROZEN","expectedVersion":0,"reasonCode":"INVESTIGATION"}`. Ledger exposes matching account control GET/POST (`ledger:controls:read/write`). OPEN/FROZEN/CLOSED controls are authoritative in the ledger, serialized under the same account locks as posting. Both incoming and outgoing entries are blocked while restricted. Closure requires FROZEN and zero balance; CLOSED never reopens. Restore OPEN through wallet service checks current KYC and PIN. Event ID retries are immutable; stale versions return 412. Operational wallet checks additionally require ledger control-read scope. Historical wallet creation/activation snapshots remain unchanged.
