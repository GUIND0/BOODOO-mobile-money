# Customer Service

Customer Service exposes creation, partner-scoped profile retrieval/completion, KYC submission and explicit operator review APIs. New customers start with KYC status `PENDING`.

Integrators own end-user onboarding, OTP delivery, and OTP verification. Customer creation performs no OTP operation, does not assert phone ownership, and does not create a PIN/wallet or approve KYC.

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
- `scope`: contains the endpoint-specific customer or KYC permission documented below. Operator review additionally requires a trusted operator claim and an independent subject.

Only `GET /actuator/health` is public. Only the explicitly scoped customer/profile, KYC and internal ownership endpoints reach business code; other endpoints remain denied. Sessions, form login, HTTP Basic, generated development users, and logout are disabled. CSRF is disabled for this stateless bearer-token API, which does not accept cookie authentication. No token issuer or development signing key is supplied.

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

Success: `201 Created`, with `Idempotency-Replayed: false` on first creation and `true` on replay. The response contains `id` (UUID), `externalReference`, `firstName`, `lastName`, `phoneNumber`, `kycStatus: PENDING`, and `createdAt` (UTC timestamp). Profile completion and KYC workflows are documented below.

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

Transaction PINs belong to Identity Service. Audit-event integration and Gateway routing remain separate work.

## Find a customer by external reference

`GET /v1/customers/by-reference?externalReference=user-001` requires `customers:read` and returns the current profile with the same ETag and no-store caching as UUID lookup. The reference is case-sensitive, trimmed and limited to 128 characters. Encode it as a URL query value. Unknown references and references owned only by another integrator both return 404 CUSTOMER_NOT_FOUND. Partner identity always comes from the signed JWT. This exact lookup helps integrators recover a customer UUID without scanning pages or recreating a customer; it does not grant additional profile permissions.

## Internal current KYC approval

`GET /internal/v1/customers/{customerId}/kyc-approval` requires `customers:kyc:read`, a trusted signed `actor_type: service` claim and a stable subject. The issuer must restrict these grants to provisioned service principals. Tenant ownership is always derived from the JWT. Unknown and foreign customers return the same 404; regular customer/KYC read scopes do not grant access.

The response contains customer ID, stored KYC status, current profile revision, active decision ID/review time (nullable), `approvalCurrent` and UTC `checkedAt`. A single SQL snapshot joins the active dossier and its own decision. Approval is current only when both customer and active decision are APPROVED, the profile retains the submitted revision plus submission/review increments, and the evidence is present with no expired document as of the checked UTC day. Expiry is inclusive: evidence remains valid on its expiry date. An expiry later than approval makes the flag false without rewriting historical decisions or the stored KYC status.

No profile, evidence references, actor identity or reason is exposed. Responses use no-store caching. Invalid UUID returns 400, insufficient scope/service identity 403, storage failure 503. This point-in-time read is an input for future activation policy, not a reservation, regulatory certification, PIN verification, financial authorization or wallet activation. Consumers must handle dependency failures and re-evaluate policy at their own controlled decision boundary; no distributed transaction or validity lease is provided.

## Internal ownership contract

`GET /internal/v1/customers/{customerId}/ownership` requires a valid customer-audience token with scope `customers:ownership:read`. It derives the integrator from the validated token, returning `204` with an empty body only when that customer belongs to the integrator. Unknown and foreign customers both return empty `404` responses. It exposes no profile data and does not change KYC status.

Identity Service uses this endpoint before transaction-PIN setup. Its caller token must be intended for both APIs and contain the ownership-read scope in addition to `pins:create`; Identity relays that token to Customer Service instead of sharing database access or accepting a tenant header. The trusted authorization server must provision these permissions. This endpoint grants no customer-update or PIN-management permission by itself.

## List customers

`GET /v1/customers?limit=50&kycStatus=PENDING` requires `customers:read`. Results contain only the authenticated integrator's customer summaries (ID, external reference, names, KYC status and creation time), without phone, address or evidence. Responses use `Cache-Control: no-store`.

`limit` defaults to 50 and must be 1–100. Optional `kycStatus` accepts PENDING, UNDER_REVIEW, APPROVED or REJECTED. Items are ordered by ascending UUID, not creation time. Pass the returned `nextAfter` UUID as `after` for the next page, keeping the same filter; null means no further matching row at query time. Invalid parameters return 400. A cursor need not belong to a customer and never grants access to another tenant. This is a live list: concurrent inserts or status changes can appear or disappear between pages, so pagination is not a snapshot or an export.

## Read a customer profile

`GET /v1/customers/{customerId}` requires `customers:read`. The integrator comes from the validated JWT. It returns the current profile and KYC status with `200` and `Cache-Control: no-store`, rather than the historical creation snapshot. Foreign and missing customers both return `404 CUSTOMER_NOT_FOUND` without profile data. Missing authentication returns 401; creation scope alone does not grant read access. Malformed UUIDs return 400. Storage errors return 503 without database diagnostics.

## Read profile update history

`GET /v1/customers/{customerId}/profile-history?limit=50&afterVersion=0` requires `customers:audit:read`, a signed operator claim and stable subject. It returns immutable manual profile-edit metadata: revision, actor ID, changed field names and timestamp, without old/new profile values. Creation, no-op updates and KYC transitions are not profile-edit events; this endpoint is not a full audit log. Normal `customers:read` does not grant access.

History is tenant/customer scoped, ordered by ascending revision. Limit is 1–100 (default 50); pass `nextAfterVersion` as `afterVersion`, keeping the customer unchanged. Null means the current end. Invalid parameters return 400; foreign and unknown customers return the same 404. Responses are not cached. Appended edits can appear on later pages; revision gaps from KYC transitions are valid.

## Complete a profile

`PATCH /v1/customers/{customerId}` requires `customers:write`, a stable nonblank JWT `sub` for audit, and `If-Match` containing the current strong ETag from GET (for example `"0"`). Missing precondition returns 428, malformed ETag 400, stale revision 412. GET/PATCH responses now include `version`, `updatedAt` and optional profile details, with the matching ETag and no-store caching. The creation response and its stored replay remain unchanged.

Allowed optional fields: firstName, lastName, dateOfBirth (ISO date strictly in the past), nationality and residenceCountry (ISO alpha-2, normalized uppercase), addressLine and city. Missing or null fields mean unchanged; at least one non-null field is required. Clearing fields, changing phone/external reference/ownership/KYC, or changing a profile while KYC is under review/approved is not supported. No legal age or eligibility rule is inferred from date of birth.

Actual changes atomically increment the revision and append actor/revision/changed-field names to immutable profile history, without duplicating profile values there. Identical no-op updates retain the revision. On an uncertain response, GET the current profile before retrying with its ETag. Storage errors roll back both profile and history. Flyway V2 preserves existing minimal profiles with revision zero and nullable enrichment fields.

## Submit and consult a KYC dossier

`POST /v1/customers/{customerId}/kyc-submissions` requires `kyc:submit`, a stable JWT sub, `Idempotency-Key`, and the profile's original strong `If-Match` revision. Birth date, nationality, residence country and address must already be complete. It snapshots that exact profile and 1–3 document references, changes KYC to `UNDER_REVIEW`, increments the customer revision, and returns 201. Profile changes and a second pending submission are then refused. This workflow does not automatically verify identity, approve KYC, activate a wallet, or enable payments.

Body example: `{"documents":[{"type":"NATIONAL_ID","reference":"vault-document-1","issuingCountry":"ML","expiresOn":"2999-12-31"}]}`. Types: NATIONAL_ID, PASSPORT, RESIDENCE_PERMIT. References are opaque identifiers (1–128 letters/digits/dot/underscore/colon/hyphen), not download URLs or raw document numbers. Use immutable versioned references in protected integrator storage. BOODOO snapshots metadata only, does not fetch documents, and does not establish their authenticity. Countries are normalized ISO alpha-2; expiry is optional and checked against UTC on a new submission. Duplicate references are rejected and ordering is canonicalized.

`GET /v1/customers/{customerId}/kyc-submissions/{submissionId}` requires `kyc:read`; it returns the dossier snapshot and review status with no-store caching. Unknown, foreign and mismatched customer/submission IDs return 404. Submission permission alone does not grant read access. Evidence stays scoped to the authenticated integrator.

Submission and idempotency state commit together. A same-key replay with the same actor, customer, original profile revision and normalized document metadata returns the original response, even after document expiry; conflicting reuse returns 409. Concurrent new keys with one profile revision yield one submission and a stale-revision response for the other. No automatic expiration of idempotency records is configured. Immutable snapshots/history contain personal data and require restricted database access and retention controls.

Flyway V3 preserves prior migrations and adds submissions, request snapshots and the active-dossier link. Stored KYC status can now represent PENDING, UNDER_REVIEW, APPROVED or REJECTED; decision APIs are separate. Storage failure rolls back the dossier, status and revision together. Submitted DTO/profile string representations are redacted.

## List a customer's KYC submissions

`GET /v1/customers/{customerId}/kyc-submissions?limit=50` requires `kyc:read` and lists historical as well as active submissions for that integrator/customer. Each summary contains dossier ID, captured profile revision, submission time and that dossier's own review status. A later resubmission never changes the rejected status of an older dossier. Profile snapshots, document references and actor identities are excluded; retrieve a dossier separately when needed.

Items use ascending UUID order, not chronological order. Limits are 1–100 (default 50); pass `nextAfter` as `after`, with null indicating the current end. This live pagination does not freeze decision changes or newly submitted dossiers. Unknown/foreign customers return 404, an owned customer without dossiers returns an empty page, malformed pagination returns 400. Responses use no-store caching.

## List dossiers available for review

`GET /v1/kyc-review-queue?limit=50` requires `kyc:review`, the signed `actor_type: operator` claim and a stable nonblank subject. It returns only the authenticated integrator's active UNDER_REVIEW dossiers with the frozen profile revision still intact and no final decision. The operator's own submissions are excluded to preserve maker/checker separation. Each entry contains submission/customer IDs, the submitted profile revision and submission timestamp; it contains no profile, document metadata or submitter identity. Read a dossier separately with `kyc:read`.

Pagination uses ascending submission UUIDs, not review priority or submission time. `limit` defaults to 50 (1–100); pass `nextAfter` as `after`, with null meaning the current end. Invalid parameters return 400. Responses use `Cache-Control: no-store`. This is a live queue, not a reservation: decisions, resubmissions and new arrivals can change subsequent pages. The decision endpoint rechecks all eligibility rules under lock, including evidence expiry; appearing here does not guarantee approval eligibility. No assignment, automatic review or financial activation occurs.

## Review a submitted KYC dossier

`POST /v1/customers/{customerId}/kyc-submissions/{submissionId}/decisions` requires `kyc:review`, a stable nonblank JWT `sub`, and the signed claim `actor_type: operator`. The trusted issuer must assign these permissions only to authenticated review operators; request-body actor fields or arbitrary headers never establish identity. The reviewer subject must differ from `submittedBy` (maker/checker separation). Stable actor IDs must identify the actual principal consistently across tokens.

Body: `{"decision":"APPROVED","reasonCode":"MANUAL_REVIEW_PASSED"}` or `{"decision":"REJECTED","reasonCode":"DOCUMENT_UNREADABLE"}`. The reason is a controlled uppercase policy code (1–64 letters/digits/underscore), not free-form personal information. `Idempotency-Key` is required. A current, undecided submission must still be the customer's active frozen profile; stale revisions, expired approval evidence and attempts to overwrite a final decision are refused.

This API records an authorized operator's explicit review decision. It does not download/validate documents, automate identity eligibility, certify regulatory compliance, create a wallet, approve a transfer or replace the operator's applicable review procedures. Operators must inspect the immutable profile/evidence version referenced by the dossier through their protected document workflow.

Success returns 201 with a decision UUID, customer/submission IDs, APPROVED/REJECTED status, reason, authenticated reviewer and review timestamp. It atomically appends immutable decision/history and idempotency records, changes the customer's KYC status, and increments its profile revision. Approval freezes the profile; rejection permits correction and resubmission. A rejected dossier remains immutable and retains its reason/history.

Same-key replay with the same reviewer and request returns the original decision, even after expiry or later resubmission; it never reapplies an old status to the current customer. Conflicting reuse returns 409. Only one final decision can exist per submission, including concurrent reviews with different keys. The active dossier ID and frozen revision are checked under the customer row lock before a new decision is accepted. SQL failures roll back the decision, customer status/revision and request snapshot together.

Dossier GET now includes an optional `decision` object (null before review), and derives the historical dossier status from its own decision, not the customer's newest submission. Both are read in one SQL snapshot to avoid mixed status/decision responses. Original POST-submission replays retain their original UNDER_REVIEW response snapshot. All dossier reads still require `kyc:read` and partner ownership.

Key errors: 403 OPERATOR_REQUIRED/ACTOR_REQUIRED/SELF_REVIEW_FORBIDDEN; 404 missing or foreign customer/dossier; 409 KYC_DECISION_EXISTS/STALE_KYC_SUBMISSION/KYC_DOCUMENT_EXPIRED/IDEMPOTENCY_CONFLICT; 503 CUSTOMER_OPERATION_UNAVAILABLE. Invalid decision/reason/unknown fields return 400. No decision-edit, appeal, approval-revocation or KYC-reopening endpoint is implemented.

Flyway V4 adds decision tables without modifying V1–V3. Tests cover human-role/scope/tenant isolation, self-review refusal, concurrent decisions and retries, evidence expiry, stale profile protection, immutable histories, correction/resubmission without replay regression, atomic rollback, and a V1-to-V4 upgrade preserving legacy creation snapshots.
