# Customer Service

Customer Service exposes BOODOO customer profiles to integrating applications. The first implemented feature is `POST /v1/customers`: create a minimal profile with KYC status `PENDING`.

Integrators own end-user onboarding, OTP delivery, and OTP verification. This API performs no OTP operation, does not assert phone ownership, and does not create a PIN, wallet, or approved KYC record.

## Run locally

Requirements: JDK 25 or later and a running Docker daemon. Maven is downloaded by the wrapper.

```sh
export CUSTOMER_DB_PASSWORD='choose-a-local-development-password'
export CUSTOMER_JWT_ISSUER_URI='https://your-authorization-server.example'
export CUSTOMER_JWT_JWK_SET_URI='https://your-authorization-server.example/jwks'
export CUSTOMER_JWT_AUDIENCE='boodoo-customer-api'
docker compose up -d --wait
./mvnw spring-boot:run
```

Run these commands from `services/customer-service`. Replace the authorization-server examples with a trusted provider. Issuer and JWK Set configuration are required; unresolved placeholders or non-HTTP(S) values fail configuration validation. Use HTTPS for deployed authorization servers and terminate application TLS at a trusted ingress.

The API listens on `8084`, overridden with `CUSTOMER_SERVER_PORT`. PostgreSQL 17 is exposed on `127.0.0.1:5435`. Database overrides are `CUSTOMER_DB_URL`, `CUSTOMER_DB_USER`, and `CUSTOMER_DB_PASSWORD`; Compose provisions `customer_db`/`customer_user`. `docker compose down` stops the development database while retaining its named volume.

```sh
curl --fail http://localhost:8084/actuator/health
```

Flyway owns the schema. JDBC is used for atomic inserts, explicit concurrency handling, and transaction-scoped idempotency locks. No other service may access this database directly.

## Partner authentication

An integrator calls the API server-to-server using an RS256-signed OAuth2 bearer JWT from the configured authorization server. BOODOO validates its signature, issuer, audience, validity window, required expiration, and `integrator_id` claim. The issuer must assign this claim from trusted partner provisioning; clients must not be allowed to choose another partner's claim.

Required token claims:

- `iss`: exactly the configured issuer.
- `aud`: contains the configured audience, default `boodoo-customer-api`.
- `exp`: an expiration accepted by the timestamp validator. Spring Security's standard clock-skew allowance applies.
- `integrator_id`: a stable partner identifier matching `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`.
- `scope`: includes `customers:write`.

Only `GET /actuator/health` is public. Authorized `POST /v1/customers` and scoped internal ownership requests reach business code; other endpoints remain denied. Sessions, form login, HTTP Basic, generated development users, and logout are disabled. CSRF is disabled for this stateless bearer-token API, which does not accept cookie authentication. No token issuer or development signing key is supplied.

See [Spring Security resource-server JWT validation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) and [OWASP partner isolation guidance](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html).

## Create a customer

The API contract is in [openapi.yaml](openapi.yaml). Calls currently go directly to Customer Service; Gateway routing is a separate change.

```sh
curl --fail-with-body http://localhost:8084/v1/customers \
  -H "Authorization: Bearer ${PARTNER_ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: onboarding-0001' \
  --data '{"externalReference":"user-001","firstName":"Awa","lastName":"Test","phoneNumber":"+22370000001"}'
```

The profile above is illustrative test data. All four fields are required. External references allow up to 128 characters, names up to 100. The phone number must use the canonical international `+` format, with 2–15 digits and no spaces or punctuation. This is a format check, not a claim that the number is assigned or verified. Leading/trailing Unicode whitespace is stripped from all fields; casing and internal content are preserved.

The caller cannot supply `id`, `integratorId`, `kycStatus`, `phoneVerified`, or `pin`. Unknown properties are rejected. Integrator ownership comes solely from the validated token, never from a submitted header or body field.

Success: `201 Created`, with `Idempotency-Replayed: false` on first creation and `true` on replay. The response contains `id` (UUID), `externalReference`, `firstName`, `lastName`, `phoneNumber`, `kycStatus: PENDING`, and `createdAt` (UTC timestamp). No public profile GET, profile update, or KYC-submission endpoint is implemented.

## Duplicates and idempotency

- `Idempotency-Key` is mandatory and matches `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`.
- Each integrator has its own namespace for idempotency keys, external references, and phone numbers. A phone number can belong to only one customer within that integrator in V1; different integrators may register the same phone/reference independently.
- The same key and normalized profile return the original response snapshot and `201`, even if the stored profile changes later.
- Reusing a key with a different normalized profile returns `409 IDEMPOTENCY_CONFLICT`.
- An existing external reference or phone with a different key returns `409 CUSTOMER_ALREADY_EXISTS`; it does not return another profile's data.
- PostgreSQL uniqueness constraints protect concurrent duplicate profiles. A transaction-scoped advisory lock serializes concurrent uses of the same integrator/key; a hash collision only serializes unrelated requests.
- Customer creation and the idempotency snapshot commit in one database transaction. Failure rolls both back. The transaction has a 10-second timeout.
- Following a lost response or `503`, retry the same profile with the same key. Do not generate a new key to resolve an uncertain outcome.
- Idempotency records currently have no automatic expiry. Their snapshots contain profile data and require the same restricted access and retention controls as customer records.

## Errors

| HTTP | Meaning |
|---|---|
| 400 | Invalid/missing profile fields, unknown properties, or invalid/missing idempotency key |
| 401 | Missing, invalid, expired, or incorrectly scoped-to-partner bearer identity |
| 403 | Valid bearer identity without `customers:write`, or a denied route |
| 409 | Duplicate customer or conflicting reuse of an idempotency key |
| 415 | Unsupported request content type |
| 503 | Database operation could not complete; retry with the same key |

Application validation/conflict/database errors use `application/problem+json` with a stable `code`. Authentication/authorization errors are handled by Spring Security. Responses do not echo rejected values or database diagnostics. The application does not log request bodies, PINs, OTPs, or bearer tokens.

## Verify

```sh
./mvnw verify
```

Tests run against real PostgreSQL 17 through Testcontainers and use an in-process JWK endpoint with ephemeral RSA keys. They verify migration, signed-token validation, partner isolation, validation, duplicate detection, normalized replay, immutable response snapshots, concurrent requests, and rollback/retry after an injected persistence failure. No production identity provider, SMS provider, credentials, or real customer data are used.

PIN setup, profile enrichment, KYC submission/review, audit-event integration, and Gateway routing will be delivered separately.

## Internal ownership contract

`GET /internal/v1/customers/{customerId}/ownership` requires a valid customer-audience token with scope `customers:ownership:read`. It derives the integrator from the validated token, returning `204` with an empty body only when that customer belongs to the integrator. Unknown and foreign customers both return empty `404` responses. It exposes no profile data and does not change KYC status.

Identity Service uses this endpoint before transaction-PIN setup. Its caller token must be intended for both APIs and contain the ownership-read scope in addition to `pins:create`; Identity relays that token to Customer Service instead of sharing database access or accepting a tenant header. The trusted authorization server must provision these permissions. This endpoint grants no customer-update or PIN-management permission by itself.
