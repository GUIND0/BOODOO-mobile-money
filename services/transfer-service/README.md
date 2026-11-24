# Transfer Service — durable XOF P2P orchestration

Transfer Service accepts an immutable P2P intent, verifies the sender's transaction PIN, records authorization, checks the current wallet/fee/risk/allowance facts, and posts one balanced ledger journal. Its durable state distinguishes a rejected request from uncertain posting and from already posted money awaiting finalization. It never writes into another service's database.

## Run and provision credentials

From this directory, configure `TRANSFER_DB_PASSWORD`, `TRANSFER_JWT_ISSUER_URI`, `TRANSFER_JWT_JWK_SET_URI`, the dependency origins and the service credentials below. Run `docker compose up -d --wait`, then `./mvnw spring-boot:run`. API defaults to 8082. PostgreSQL now uses **127.0.0.1:5445**, replacing the initialization's 5433 to avoid the Wallet Service database port. Database/user remain transfer_db/transfer_user; TRANSFER_DB_URL/USER/PASSWORD and TRANSFER_SERVER_PORT override defaults. Flyway owns the schema; JDBC replaces unused JPA bootstrap.

Incoming caller tokens require the trusted issuer, expiry, audience `boodoo-transfer-api`, signed integrator_id and a stable signed service principal. POST requires transfers:write; reads require transfers:read. The integrating backend remains responsible for user consent, session authentication and OTP. No OTP is sent or verified by BOODOO.

`TRANSFER_SERVICE_TOKENS_JSON` is an operator-provisioned secret-store/deployment mapping of integrator IDs to **dedicated service JWTs**, not caller credentials. Example shape only: `{"partner-a":"<provisioned service JWT>"}`. Each token is signature/issuer/expiry/audience validated by this service and must have the same integrator_id, actor_type service, and sub exactly transfer-service. Missing/expired/wrong-tenant credentials fail closed. No real tokens belong in source or logs. This initial credential adapter reads deployment configuration; automated client-credentials exchange/refresh is a release integration gate. Rotate through the deployment/secret-store mechanism.

Service tokens must target Transfer, Wallet, Customer, Identity, Pricing, Limits, Risk Compliance, Ledger, Audit and Notification APIs. Grant only the required scopes: wallets:eligibility:read, customers:kyc:read, customers:ownership:read, pins:read, pins:verify, pricing:quotes:write, limits:reservations:write, limits:reservations:settle, risk:evaluations:write, ledger:accounts:read, ledger:journals:write, ledger:journals:read, audit:events:write and notifications:write. A recovery principal additionally needs transfers:resume. The caller token is never forwarded to these financial dependencies.

`TRANSFER_FEE_ACCOUNTS_JSON` maps integrator IDs to pre-provisioned INCOME/CREDIT XOF account UUIDs. Fees greater than zero require an owned account, checked through Ledger Service; zero-fee transfers need no fee account. Empty credential/fee mappings provide no implicit production grants/accounts. Dependency origins use TRANSFER_WALLET_BASE_URL, TRANSFER_PRICING_BASE_URL, TRANSFER_IDENTITY_BASE_URL, TRANSFER_LIMITS_BASE_URL, TRANSFER_RISK_BASE_URL, TRANSFER_LEDGER_BASE_URL, TRANSFER_AUDIT_BASE_URL and TRANSFER_NOTIFICATION_BASE_URL. Defaults target the corresponding local service ports. Use HTTPS in deployment. Origins reject embedded credentials/path/query, redirects are disabled, responses are bounded to 64 KiB, and calls have bounded timeouts.

## Create, inspect and retry

```sh
curl --request POST 'http://127.0.0.1:8082/v1/transfers' \
  --header "Authorization: Bearer $CALLER_TOKEN" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: p2p-user-action-001' \
  --data '{"senderWalletId":"00000000-0000-0000-0000-000000000010","recipientWalletId":"00000000-0000-0000-0000-000000000020","amount":100,"maxFee":20,"currency":"XOF","pin":"739152"}'
```

UUIDs must differ. Amount is a positive JSON integer in XOF minor units; maxFee is a nonnegative integer consent cap, added to the principal. Fractional/coerced monetary values and numeric PINs are rejected. PIN must be six ASCII digits as a string. The PIN is used only in the initial Identity Service verification call; it is not stored, hashed into the request fingerprint, returned or logged. No bearer token is stored in a transfer record.

The key matches `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` and binds the signed tenant/caller subject to the exact wallet IDs, principal, fee cap and currency. Reusing a key with changed financial intent or another caller conflicts with 409. Exact retries resolve the existing operation; the response contains its **live state**, not an immutable creation snapshot. Once the same intent is authorized, replay/resume does not perform another PIN comparison or authorize a different payment. Changing the PIN field on replay never changes the financial intent or grants authority for a new action. Clients must retain the original key across timeouts and must never create a new key merely because a response was lost.

POST returns 201 for COMPLETED, 422 for definitively REJECTED before financial reservation/posting, or 202 for accepted work requiring retry/recovery. Location identifies `/v1/transfers/{id}`; Idempotency-Replayed indicates reuse of an existing intent. Responses expose principal/fee/state/journal metadata, never PIN/credentials. GET by ID requires transfers:read and is tenant-scoped. GET `/v1/transfers?walletId=<uuid>` returns live UUID-ordered history with limit 1–100 (default 50), optional after and nextAfter; this is not a chronological export or proof that every recorded attempt was authorized. Foreign/missing IDs are indistinguishable, and an empty wallet namespace returns an empty list.

## Financial and recovery boundary

State transitions are database guarded:

CREATED → AUTHORIZED → RESERVING → RESERVED → POSTING → POSTED → COMPLETED.

Only CREATED/AUTHORIZED can become REJECTED automatically. Before authorization, current active wallet/customer/account bindings, a fresh quote within the fee cap, and a successful sender PIN check are required. Configured risk must ALLOW. Allowance covers **principal plus fee**, using its captured UTC authorization period. Just before posting, both wallet bindings and risk/quote/reservation validity are checked again. These remote facts are observations, not a distributed validity lease; coordinated revocation/expiry and cancellation barriers require additional release work.

The journal debits the sender liability by principal+fee and credits recipient liability by principal and the configured income account by fee. Zero fees omit the income entry. All entries post atomically through Ledger Service under a deterministic key/reference. Authoritative ledger account locks prevent double spending. Receipt currency, reference and every account/side/amount must exactly match the immutable intent before recording POSTED.

Once posting could have started, a timeout/error never means failure. Recovery first queries the ledger by transfer reference; a matching existing receipt finalizes the original posting without another debit. A missing receipt permits only a same-key posting retry while captured execution facts remain valid. Expired/denied/unknown facts remain pending for controlled investigation; allowance is never automatically released after uncertain outcomes. This conservative workflow can retain capacity until explicit reconciliation/cancellation is implemented. It does not provide an authoritative cancellation or reversal API.

POSTED means money is already accounted for, even if limits confirmation or event finalization fails. Limits confirmation, one audit event and two notification-inbox records use stable references/IDs so retries are safe. Only after those durable records are acknowledged does the status become COMPLETED. This is inbox recording, not SMS/email/webhook delivery.

A 120-second database lease fences state updates. External calls occur outside database transactions; stale workers cannot advance local state, and ledger idempotency prevents duplicate financial effects. Private GET `/internal/v1/transfers/recoverable` returns bounded recoverable IDs (optional after/limit); POST `/internal/v1/transfers/{id}/resume` resumes an authorized intent. Both require transfers:resume and the exact transfer-service subject. A worker cannot supply a missing initial PIN authorization. Scheduling/operating the recovery worker and resolving permanently blocked work are release gates; no automatic scheduler is installed by this PR.

## Verification and dependencies

`./mvnw -o clean verify` uses PostgreSQL 17, ephemeral signed JWTs and real bounded HTTP calls against controlled peers. Tests cover fee/PIN/risk denial, caller/service credential separation, lost ledger acknowledgement after simulated commit, already-posted notification failure, fenced concurrent retries, tenant/scopes, immutable intent and integer/PIN coercion. These are service integration tests, not a claim that a deployed whole-stack/settlement test has passed.

The normal path requires the balanced-journal, wallet-activation, pricing, allowance, risk, audit and notification APIs from this release batch. Missing dependencies fail closed. Cash-in/out, merchant payments, linked reversal/refund, automatic reconciliation/recovery, signed webhooks, credential refresh, full-stack failure testing and operational deployment controls remain work before the first pilot release.
