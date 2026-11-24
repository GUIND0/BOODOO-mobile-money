# API Gateway — bounded public API routing

The gateway provides the public JSON entry point for Customer, Identity, Wallet, Transfer and notification-inbox APIs. It validates the trusted partner JWT, then forwards the caller token unchanged to a fixed, operator-configured destination. Destination services retain their own authorization, ownership, actor and business rules. The gateway issues no tokens, verifies no OTP, compares no PIN and stores no business data.

## Run and configure

Set API_GATEWAY_JWT_ISSUER_URI and API_GATEWAY_JWT_JWK_SET_URI, then run `./mvnw spring-boot:run` from this directory. API_GATEWAY_SERVER_PORT defaults to 8080. Tokens require issuer/signature, expiry, trusted integrator_id and audience `boodoo-gateway-api` (override API_GATEWAY_JWT_AUDIENCE), plus the audience/scopes required by the destination. Wallet/Identity ownership flows also need the Customer audience. Transfer callers need the gateway/transfer audiences and transfer permission; dedicated private downstream credentials are provisioned in Transfer Service, not added to caller tokens by the gateway.

Configure fixed HTTP(S) origins with API_GATEWAY_CUSTOMER_BASE_URL (8084), API_GATEWAY_IDENTITY_BASE_URL (8086), API_GATEWAY_WALLET_BASE_URL (8081), API_GATEWAY_TRANSFER_BASE_URL (8082) and API_GATEWAY_NOTIFICATION_BASE_URL (8087). Defaults use 127.0.0.1. Origins cannot contain credentials, paths, queries or fragments. Deployed traffic must use HTTPS. No request/header/body value can select an arbitrary destination.

| Public path | Destination |
| --- | --- |
| `/v1/customers` and customer profile/KYC paths | Customer Service |
| `/v1/customers/{id}/pin` and `/pin/verify` | Identity Service |
| `/v1/wallets` and wallet metadata reads | Wallet Service |
| `/v1/transfers` and transfer reads/history | Transfer Service |
| `/v1/customers/{id}/notifications` and acknowledgement | Notification Service |

Only GET, POST and PATCH public routes are forwarded. Internal ledger, allowance, risk, activation, audit, reconciliation and worker-control paths remain inaccessible through this gateway. Unknown public routes return 404; private paths/methods are denied. GET /actuator/health is public with details hidden; it is liveness, not proof that every dependency is available. The gateway uses explicit bounded Spring MVC forwarding; the unused initial Spring Cloud bootstrap is removed. There is no dynamic route registry, discovery service or datastore.

## Request and response boundaries

Public paths allow only the ASCII resource characters used by these APIs; encoded/traversal/double-slash paths are rejected before forwarding. Queries retain their raw encoding and are bounded to 8192 characters. JSON requests and upstream responses are bounded to 64 KiB. GET bodies, malformed JSON, non-JSON request bodies and compressed bodies are rejected. Connect/request/overall dependency waits are bounded, and redirects are never followed.

Authorization, Content-Type, Idempotency-Key and conditional If-Match/If-None-Match are the only application request headers relayed or constructed. Host, cookies, tenant/actor overrides and arbitrary forwarding headers are excluded. PIN strings retain leading zeros and request bodies/tokens are not logged. Responses preserve relevant status, Content-Type, ETag, relative public Location, Idempotency-Replayed, Retry-After and bearer challenge headers. Cookies/hop-by-hop headers are not forwarded; responses use no-store caching.

A timeout can occur after a destination accepted a financial operation. The gateway does not invent a failed payment or retry a write automatically. Clients must keep the original idempotency key and follow the destination's status/recovery contract. In particular, transfer POST may return 202 or may need a same-key retry after gateway 503; creating a new key can create another payment.

Optional API_GATEWAY_ALLOWED_ORIGINS is a comma-separated list of exact browser origins without credentials/path/wildcard. Defaults permit no cross-origin browser access. CORS allows only documented headers and GET/POST/PATCH, exposes the relevant response metadata, and never enables cookie credentials. An allowed preflight grants no bearer authorization for the actual request.

## Contract and monetary precision

`openapi.yaml` combines the public paths of this feature batch with namespaced downstream schemas. Private operations are omitted. This API surface requires merging the corresponding service feature PRs; each service remains its own contract/authorization owner.

The gateway integration change also adds a boot-level scalar contract to Ledger, Pricing, Limits and Risk Compliance: integer money cannot silently accept/truncate JSON floating values or numeric strings. Numeric JSON cannot silently become a PIN/string. Each module has a lightweight Spring/Jackson contract test, independent of its business endpoints. Transfer Service includes the same policy and real HTTP rejection tests. This prevents ambiguous financial payloads at both direct internal APIs and the public edge.

## Verify and release boundary

`./mvnw -o clean verify` runs stateless HTTP integration tests with ephemeral signed JWTs and real controlled upstream servers. No database or production provider is required. Tests cover routing, token/header transparency, conditional updates, PIN string preservation, private-route exclusion, path/body/response limits, redirects, no-content replies and exact-origin CORS.

Before a pilot, run the complete merged service stack and failure/recovery scenarios, provision/refresh service credentials, configure the operator policies and private-network boundaries, and operate the recovery/event-delivery processes. Production rate limiting, external settlement integration, observability, backup/restore and deployment controls are separate release work. This PR is a routing/input-contract foundation, not a production-readiness claim.
