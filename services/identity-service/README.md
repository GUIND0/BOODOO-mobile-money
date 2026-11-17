# Identity Service — transaction PIN creation

Identity Service exposes transaction-credential APIs for BOODOO integrations. `POST /v1/customers/{customerId}/pin` sets the first transaction PIN for an existing customer owned by the authenticated integrator. End-user login, OTP delivery/verification and user consent remain with the integrating application.

This change does not implement PIN verification, PIN change, PIN recovery, token issuance, wallets, or KYC approval. Creating a PIN does not change the customer's KYC status.

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
- `scope`: includes `pins:create` and `customers:ownership:read`.

Identity requires `pins:create` at the API boundary. It relays the caller's bearer token to the fixed configured Customer Service origin for `GET /internal/v1/customers/{customerId}/ownership`. Customer Service independently validates its token audience, issuer and ownership-read scope, then derives the integrator from the token and checks the customer in its own database. No tenant header, query parameter, customer profile or PIN is forwarded.

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

An authenticated, validated request with confirmed customer ownership consumes one setup attempt before creation or replay. PostgreSQL atomically limits each integrator/customer pair to **10 attempts in a 15-minute window**, including correct replays and conflicting retries. This bounds guesses against idempotency fingerprints. It is separate from future PIN-verification limits and from Gateway traffic controls.

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
