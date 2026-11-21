# Identity Service — transaction PIN APIs

Identity Service exposes transaction-credential APIs for BOODOO integrations. `POST /v1/customers/{customerId}/pin` sets the first transaction PIN for an existing customer owned by the authenticated integrator. End-user login, OTP delivery/verification and user consent remain with the integrating application.

The service supports first PIN setup and PIN verification. PIN change/recovery, token issuance, wallets and KYC approval remain separate work. Neither PIN operation changes the customer's KYC status.

## Read PIN configuration status

`GET /v1/customers/{customerId}/pin` requires `pins:read`, `customers:ownership:read` and both service audiences. Customer ownership is checked remotely on every read before local state is inspected. Success returns `200 {"customerId":"<uuid>","configured":true}` (false when the owned customer has no PIN), with `Cache-Control: no-store`. It reveals no hash, PIN value, timestamps, protection key version, lock state or comparison counters. It does not compare a PIN, consume comparison budgets, reset a lock or grant payment authorization. The integrating application must still obtain the appropriate end-user consent.

Unknown/foreign customers both return 404. Missing read scope returns 403, missing ownership permission is rejected by Customer Service with 403, invalid UUID returns 400. Ownership dependency failure returns 503 OWNERSHIP_UNAVAILABLE; local storage failure returns 503 PIN_STATUS_UNAVAILABLE. Neither failure is converted into `configured:false`.

## Run locally

Requirements: JDK 25 or later, a running Docker daemon, and the Customer Service version containing the ownership endpoint described below. Run commands from `services/identity-service`.

```sh
export IDENTITY_DB_PASSWORD='choose-a-local-development-password'
export IDENTITY_JWT_ISSUER_URI='https://your-authorization-server.example'
export IDENTITY_JWT_JWK_SET_URI='https://your-authorization-server.example/jwks'
export IDENTITY_PIN_PEPPER='<persistent Base64 secret from your secret store>'
export IDENTITY_PIN_PEPPER_VERSION='v1'
export IDENTITY_CUSTOMER_BASE_URL='http://127.0.0.1:8084'
docker compose up -d --wait
./mvnw spring-boot:run
```

Generate the pepper once using a cryptographically secure generator (for example `openssl rand -base64 32`), store it in a protected secret store, and reuse it across application restarts. It must contain at least 32 random bytes after Base64 decoding. There is no default development pepper. Never commit or print real PINs, bearer tokens, peppers, or database passwords in application logs.

Application port defaults to `8086` (`IDENTITY_SERVER_PORT`); PostgreSQL 17 is exposed on `127.0.0.1:5437`. Database overrides: `IDENTITY_DB_URL`, `IDENTITY_DB_USER`, `IDENTITY_DB_PASSWORD`. Compose provisions `identity_db`/`identity_user`; use the overrides for an independently provisioned database. `docker compose down` retains the database volume.

Use HTTPS for deployed API and service-to-service traffic. Configure payload redaction at ingress, proxies and monitoring systems as well as in the application. The production token issuer and secret store are external dependencies, not bundled services.

## Bearer authorization and ownership

The incoming RS256 JWT must be issued by the trusted authorization server configured consistently in Identity and Customer Services. Identity validates signature, issuer, expiry, integrator claim, and both intended audiences:

- `integrator_id`: `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`, assigned by trusted partner provisioning.
- `aud`: includes `boodoo-identity-api` and `boodoo-customer-api` by default. Override with `IDENTITY_JWT_AUDIENCE` and `IDENTITY_CUSTOMER_AUDIENCE` when configuring the corresponding resources.
- `exp`: required. Standard Spring Security clock skew applies.
- Setup scope: `pins:create` plus `customers:ownership:read`.
- Verification scope: `pins:verify` plus `customers:ownership:read`.

Identity requires `pins:create` for setup and `pins:verify` for verification at the API boundary. It relays the caller's bearer token to the fixed configured Customer Service origin for `GET /internal/v1/customers/{customerId}/ownership`. Customer Service independently validates its token audience, issuer and ownership-read scope, then derives the integrator from the token and checks the customer in its own database. No tenant header, query parameter, customer profile or PIN is forwarded.

The ownership endpoint returns `204` only for the caller's own customer, otherwise `404`, without profile data. Missing ownership scope returns `403`. Identity refuses to create a PIN if ownership cannot be confirmed, including dependency timeouts and redirects. Connect timeout is 2 seconds; the ownership request has a 3-second deadline. Ownership is rechecked on retries too. Customer-to-integrator ownership is an immutable boundary in the current customer APIs.

Integrators must authorize the end-user's intent before submitting a PIN and restrict use of their backend credentials. BOODOO does not accept a client-provided `otpVerified` flag or perform OTP verification here.

Only `GET /actuator/health` is public. Unimplemented routes stay denied. Cookie authentication, sessions, form login, HTTP Basic, generated development users and logout are disabled. CSRF is disabled for this stateless bearer-token API.

## API

The contract is [openapi.yaml](openapi.yaml). Calls target Identity Service directly; Gateway routing is separate.

```sh
curl --request POST "http://127.0.0.1:8086/v1/customers/${CUSTOMER_ID}/pin" \
  --header "Authorization: Bearer ${PARTNER_ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: pin-setup-0001' \
  --data '{"pin":"739152"}'
```

The PIN is a JSON **string** of exactly six ASCII digits; leading zeros are preserved. Whitespace, numeric JSON values, repeated digits and ascending/descending digit sequences (including wraparound) are rejected. The example is test data. Unknown fields are rejected. The request object's string representation is redacted.

Success: `201 Created` with `customerId`, `status: CREATED`, `createdAt`, and `Idempotency-Replayed: false` (or `true` for a replay). Neither PIN, hash nor protection-key metadata is returned.

## Storage and idempotency

- Flyway owns the schema. JDBC writes the credential and its idempotency record in one transaction with a 10-second timeout; failure rolls both back.
- PIN protection uses Spring Security's Argon2id encoder with a random 16-byte salt, 32-byte output, 19,456 KiB memory, two iterations and parallelism one. Its input is a Base64-encoded HMAC-SHA256 of a versioned domain, integrator, customer UUID and PIN, using the external pepper. The PIN is never stored in plaintext or reversible encryption.
- Request fingerprints use a different HMAC domain and the same protected context, not a plain digest of a low-entropy PIN. HMAC inputs have explicit byte-length prefixes.
- `Idempotency-Key` is mandatory and matches `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. Use an opaque key unrelated to the PIN. Keys are scoped to the integrator.
- The same key, customer and PIN replay the original creation response without rehashing or changing the credential. A different customer or PIN returns `409 IDEMPOTENCY_CONFLICT`.
- A different key for a customer with an existing PIN returns `409 PIN_ALREADY_EXISTS`, regardless of the submitted PIN. Creation cannot replace a PIN.
- Transaction-scoped advisory locks serialize key reuse and creation for a customer. Database primary keys prevent duplicates; update/delete triggers protect the creation records. Administrative database privileges remain outside this API boundary.
- Protection-key versions are stored alongside hashes and keyed request fingerprints. This first implementation supports one configured version; replay for a different stored version returns `503 PIN_KEY_UNAVAILABLE`. Do not replace a pepper under the same version. A future rotation procedure must retain old key versions and coordinate credential/fingerprint migration; this change does not implement rotation or recovery.

The hashing and pepper approach follows [OWASP storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) and uses [Spring Security's password encoder](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html). It is a software transaction PIN, not a payment-card PIN/HSM implementation.

## Attempt limits

An authenticated, validated request with confirmed customer ownership consumes one setup attempt before creation or replay. PostgreSQL atomically limits each integrator/customer pair to **10 attempts in a 15-minute window**, including correct replays and conflicting retries. Sensitive creation replays additionally participate in the shared comparison/lockout policy below, so switching endpoints cannot bypass it. Gateway traffic controls remain separate.

The attempt budget commits independently, so a later conflict or persistence failure cannot roll it back. `429 PIN_SETUP_RATE_LIMITED` includes `Retry-After: 900`. The window resets when the next accepted request arrives after expiry. Budget rows currently have no automatic cleanup. Dependency failures before ownership confirmation do not consume a PIN-setup attempt.

## Errors

| HTTP | Meaning |
|---|---|
| 400 | Invalid customer UUID, PIN body, unknown fields, or missing/invalid idempotency key |
| 401 | Invalid/missing bearer identity, expiration, integrator claim or intended audiences |
| 403 | Missing PIN-creation or customer-ownership permission; denied route |
| 404 | Customer missing or owned by another integrator |
| 409 | Existing PIN or conflicting idempotency-key reuse |
| 415 | Unsupported request content type |
| 429 | PIN setup attempt limit exceeded |
| 503 | Ownership/storage unavailable or stored protection-key version unavailable |

Application errors use `application/problem+json` with a stable `code`, without rejected PIN values, hashes or database diagnostics. For an uncertain outcome, retry the same customer, key and PIN after the indicated delay; never treat a timeout as authorization to replace a credential.

## Verify

```sh
./mvnw verify
```

Integration tests use real PostgreSQL 17, ephemeral RSA signing keys and an HTTP Customer Service contract fixture. They cover actual signed-token validation, ownership refusal, token relay, Argon2id/HMAC storage, leading-zero preservation, redaction, request validation, idempotency, no-overwrite behavior, concurrency, rate limiting, dependency failures and transaction rollback. Customer Service has separate real-database tests for the ownership endpoint and tenant isolation.

## Verify a transaction PIN

```sh
curl --request POST "http://127.0.0.1:8086/v1/customers/${CUSTOMER_ID}/pin/verify" \
  --header "Authorization: Bearer ${PARTNER_ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data '{"pin":"739152"}'
```

The token needs `pins:verify`, `customers:ownership:read`, and the same two intended audiences as setup. The request accepts exactly six ASCII digits as a JSON string, preserving leading zeros. It does not reapply creation strength rules: any syntactically valid six-digit guess is compared and accounted for. Unknown fields, numeric JSON values and whitespace are rejected. No idempotency key is required or used to deduplicate verification attempts.

A valid PIN returns **204 No Content** with `Cache-Control: no-store`, without credential data, tokens or sessions. A wrong PIN returns **422 PIN_INVALID**, until lockout applies. Missing PIN configuration for an owned customer returns **404 PIN_NOT_CONFIGURED**. Foreign and unknown customers still receive **404 CUSTOMER_NOT_FOUND** through the ownership boundary. Stored pepper-version mismatch returns **503 PIN_KEY_UNAVAILABLE**, without treating it as a wrong PIN. Storage failure returns **503 PIN_VERIFICATION_UNAVAILABLE**, without disclosing PIN correctness or database diagnostics.

This endpoint confirms the supplied PIN only at the time of the call. Its 204 response is not a reusable payment authorization or a proof bound to a particular transaction. Transaction-specific authorization and server-side enforcement will be designed with the financial workflows, following [OWASP transaction authorization guidance](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html).

### Shared comparison and lockout policy

- Every call rechecks customer ownership before accessing credentials, including while locally locked. Ownership failures do not compare a PIN or reset failed attempts.
- A successful comparison resets the consecutive-failure counter. **Five consecutive failed comparisons** set a **15-minute lock**, including the fifth failure. While locked, even a correct PIN returns **429 PIN_VERIFICATION_LOCKED**, and no comparison is performed. Lock expiry resets the failure counter on the next comparison.
- Consecutive failures persist across rate-window boundaries until success or lock expiry. The limit is not scoped to an HTTP connection, a bearer-token instance or an idempotency key.
- At most **30 completed comparisons per integrator/customer per 15-minute window** are allowed, including successful verification and sensitive creation replays. Exhaustion returns **429 PIN_VERIFICATION_RATE_LIMITED**. This limits hashing work even for repeated correct submissions.
- Both 429 codes include the conservative `Retry-After: 900`; the actual remaining window may be shorter. Waiting or token refresh does not reset persisted counters early.
- Creation replay with the original customer/key also compares a PIN via the keyed request fingerprint. These comparisons use the same counters and lockout as verification. A mismatch returns its existing 409 code before the fifth failure, then the shared 429 lock. Changing to a different setup key never replaces a PIN or tests whether it matches.
- PostgreSQL stores the state and serializes comparisons across application instances. The database clock controls expiry. A matching verification never changes or rehashes the stored credential.
- Verification business failures commit counters; they are not rolled back by 422/409/429 responses. Creation replays join the same connection/transaction, and their outer transaction also commits these business failures. SQL/infrastructure failures roll back their state updates and never report a successful comparison. Following a lost response or storage error, do not infer that no attempt was consumed.
- The earlier setup-request budget remains independent and still applies. Shared comparison-state rows currently have no automatic cleanup. All Identity replicas must use this version to enforce the same cross-endpoint policy.

The policy follows the principles of [OWASP authentication throttling and account lockout](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html). Production Gateway controls should additionally constrain total traffic per integrating application.

### Upgrade and verification coverage

Flyway applies `V2__pin_verification_state.sql` after the unchanged V1 schema; no credential or secret migration is required. Existing pepper and issuer configuration stay unchanged. Provision the new `pins:verify` scope only to appropriate integration clients. PIN creation still requires its separate scope.

Tests cover verification success/failure, leading zeros, secret redaction, JWT/scopes, ownership loss, missing credentials/keys, persisted failure counters, successful reset, lock expiry, rate-window expiry, shared creation/verification lockout, concurrent comparisons, and storage rollback. The suite uses a two-connection pool and exercises concurrent creation replays across different customers to catch nested-transaction pool starvation.
