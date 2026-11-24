# Wallet Service — pending wallet provisioning

Wallet Service provisions one XOF wallet per integrator/customer/currency and exposes its metadata. New wallets are **PENDING**. Privileged activation and an internal eligibility read are described below; no balance mutation, funding or transfer execution is provided by this service. Provisioning alone does not establish KYC approval, PIN verification or financial eligibility.

## Run locally

Requires JDK 25+, Docker, a configured authorization server and Customer Service with the internal ownership API. From this directory:

```sh
export WALLET_DB_PASSWORD='choose-a-local-development-password'
export WALLET_JWT_ISSUER_URI='https://your-authorization-server.example'
export WALLET_JWT_JWK_SET_URI='https://your-authorization-server.example/jwks'
export WALLET_CUSTOMER_BASE_URL='http://127.0.0.1:8084'
docker compose up -d --wait
./mvnw spring-boot:run
```

The API defaults to port 8081 (`WALLET_SERVER_PORT`). This directory's Compose provisions PostgreSQL 17 at `127.0.0.1:5433` with database `wallet_db`, user `wallet_user` and a persistent volume. This replaces the skeleton's default connection port 5432. Override `WALLET_DB_URL`, `WALLET_DB_USER` and `WALLET_DB_PASSWORD` to use an independently provisioned database. Existing root development Compose configuration is independent. Flyway owns the schema; no Hibernate schema generation is used. `docker compose down` retains the volume.

## Authentication and ownership

Both APIs require an RS256 bearer token from the configured trusted issuer with expiry, an `integrator_id` matching `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`, and audiences for both Wallet and Customer Services. Defaults are `boodoo-wallet-api` and `boodoo-customer-api`, configurable with `WALLET_JWT_AUDIENCE` and `WALLET_CUSTOMER_AUDIENCE`.

Creation requires `wallets:write`; reads require `wallets:read`. Both need `customers:ownership:read` for the Customer Service ownership request. Tokens are relayed only to the configured Customer Service origin, with bounded timeouts and redirects disabled. Use HTTPS for deployed API and service traffic. Tenant/owner/status fields in headers or request bodies never override validated ownership. The authorization server and integrating application remain responsible for partner provisioning and appropriate end-user authorization. BOODOO does not send or validate OTPs.

Ownership is checked before creation's database transaction, on every replay, and before returning a wallet read. No Customer Service database is accessed. A positive ownership check is a point-in-time assertion, not a distributed transaction or financial eligibility decision. A remote failure never creates a wallet or returns unverified metadata. Unknown and foreign customers return identical 404 errors; foreign wallet IDs are indistinguishable from missing ones.

## Create and read

```sh
curl --request POST 'http://127.0.0.1:8081/v1/wallets' \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: wallet-create-001' \
  --data '{"customerId":"00000000-0000-0000-0000-000000000001","currency":"XOF"}'
```

`POST /v1/wallets` returns 201 with `id`, `customerId`, `currency`, `status: PENDING`, `createdAt`, a relative `Location`, `Cache-Control: no-store` and `Idempotency-Replayed`. Only XOF is supported in V1; currency is trimmed and uppercased. Customer UUID and currency are required; unknown body fields are rejected. No client-provided balance/status/tenant is accepted.

`GET /v1/wallets/{walletId}` returns 200 with the same metadata and no-store caching, after tenant filtering and a fresh customer ownership check. It does not return a balance or imply payment readiness.

Keys must match `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. A same-key request for the same normalized customer/currency returns the immutable original creation response. Different payload reuse returns 409 IDEMPOTENCY_CONFLICT. A different key for an existing customer/currency returns 409 WALLET_ALREADY_EXISTS; it does not silently associate that new key with the existing wallet. Keys are unique per integrator. On an uncertain creation outcome, retry with the original key. No automatic expiration of creation snapshots is configured.

A transaction commits wallet and creation snapshot together. Per-key advisory locks serialize retries; a unique tenant/customer/currency constraint prevents duplicate wallets across keys, including concurrent requests. Creation snapshots are immutable. Versioned constraints distinguish unbound PENDING wallets from ACTIVE wallets with an accounting account and activation timestamp. No event publication, financial accounting or distributed activation workflow is included.

## Errors and verification

Errors use problem details with stable `code` values for application failures: 400 INVALID_REQUEST/UNSUPPORTED_CURRENCY, 403 OWNERSHIP_FORBIDDEN, 404 CUSTOMER_NOT_FOUND/WALLET_NOT_FOUND, 409 IDEMPOTENCY_CONFLICT/WALLET_ALREADY_EXISTS and 503 OWNERSHIP_UNAVAILABLE/WALLET_OPERATION_UNAVAILABLE. Missing/invalid authentication is 401; missing endpoint scope is 403; unsupported content type is 415. Errors do not echo payloads or SQL diagnostics. Apply token/payload redaction at ingress and monitoring as well as application level.

Run `./mvnw -o clean verify` with Docker. Integration tests use PostgreSQL 17 and ephemeral signed JWTs, exercise real HTTP ownership calls against a controlled server, and cover authorization, tenant isolation, canonical retries, concurrent creation, duplicate constraints, immutable snapshots and rollback/retry after an injected database failure.

## Controlled accounting-backed activation

POST /internal/v1/wallets/{id}/activate requires wallets:activate, signed actor_type service, a stable subject and Idempotency-Key. It persists a single actor/key intent per wallet, checks current KYC approval and PIN configuration, creates a deterministic XOF LIABILITY/CREDIT ledger account using a deterministic idempotency key, verifies its current balance is zero, rechecks eligibility, then atomically binds the account and marks the wallet ACTIVE. Calls occur outside database transactions. This requires the current-KYC and balanced-journal APIs, including ledger account balance reads. Dependency failures leave the wallet PENDING; retry the original key and service subject. Account creation may already have succeeded on a timeout, so it is retried with the same deterministic key and never guessed from a duplicate code. A pending intent may own an unused account until recovery.

Configure WALLET_IDENTITY_BASE_URL and WALLET_LEDGER_BASE_URL alongside WALLET_CUSTOMER_BASE_URL. The relayed token must target Wallet, Customer, Identity and Ledger APIs and grant customers:kyc:read, pins:read, customers:ownership:read, ledger:accounts:write and ledger:balances:read. External authorization-server provisioning must restrict these grants to the intended integration service. Activation checks PIN configuration only, not PIN correctness or user consent.

GET /internal/v1/wallets/{id}/operational-state requires wallets:eligibility:read and a service identity. It returns wallet/customer/account IDs, currency, lifecycle state, eligible and checkedAt; ACTIVE wallets recheck fresh current KYC and PIN configuration. Explicit policy failure yields eligible false; dependency failure returns 503 rather than false. Results are point-in-time observations, not a lease or a distributed lock. Financial workflows must recheck policy at their own decision boundary. Activation does not reserve or move money. Freeze/unfreeze, closure, operator appeals and distributed revocation guarantees remain separate work.

Activation replays preserve the original completed response without reactivating anything. Wallet creation replays still return their original PENDING snapshot after activation; public wallet GET reports live ACTIVE state. Database constraints keep activation binding and state consistent, and completed activation records are immutable. Do not interpret historical replay as current eligibility.
