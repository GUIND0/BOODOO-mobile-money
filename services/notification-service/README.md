# Notification Service — integrator notification inbox

POST /internal/v1/notifications requires notifications:write and a trusted service producer. Body id/customerId/reference UUIDs and template WALLET_ACTIVATED, TRANSFER_POSTED or TRANSFER_REJECTED. The notification ID is producer-bound idempotency; exact retries return the original UNREAD snapshot even after acknowledgement. The producer must verify authoritative customer/business references before emitting; this service records assertions in the signed tenant namespace and does not independently certify a transfer or customer relationship.

GET /v1/customers/{customerId}/notifications requires notifications:read; POST /v1/customers/{customerId}/notifications/{id}/acknowledgement requires notifications:ack. Both require a provisioned service identity; end-user access/consent remains with the integrating backend. Reads are tenant/customer scoped, no-store and live UUID-paginated with limit 1–100 (default 50), optional after and nextAfter. An empty namespace returns an empty list without revealing customer existence. Acknowledgement is idempotent and records the first authenticated acknowledging service/time; unknown/foreign/wrong-customer IDs return the same 404.

UNREAD and ACKNOWLEDGED represent inbox state only. No SMS, email, push provider, webhook or OTP is sent. No arbitrary message, recipient contact, evidence, PIN or monetary payload is accepted. The integrator retrieves authoritative details using the opaque reference. Metadata is immutable except its first acknowledgement. Producer outbox delivery and external notification delivery remain separate work.

## Run and verify

Set `NOTIFICATION_DB_PASSWORD`, `NOTIFICATION_JWT_ISSUER_URI` and `NOTIFICATION_JWT_JWK_SET_URI`, then run `docker compose up -d --wait` and `./mvnw spring-boot:run` from this service. Existing DB/API ports are retained. Flyway owns the schema; JDBC replaces the unused JPA initialization. `./mvnw -o clean verify` exercises real PostgreSQL 17 and ephemeral signed JWTs.

Tokens require the configured issuer, expiry, audience `boodoo-notification-api`, signed integrator_id, signed actor_type service and a stable subject. Scopes are checked independently. Tenant/actor headers cannot override claims. Use HTTPS in deployed traffic. Invalid requests return 400, authentication 401, authorization 403, unknown/foreign resources 404, state/idempotency conflicts 409, and storage unavailability 503 with sanitized problem details. No end-user OTP is sent or verified.
