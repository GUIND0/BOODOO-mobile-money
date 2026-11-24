# BOODOO 0.1 API pilot

Merge the feature PRs before the release operations PR. This pilot includes tenant-scoped customer/KYC/PIN/wallet APIs, durable transfers and merchant payments, controlled manual cash accounting, linked internal reversals, signed webhooks and statement reconciliation. OTP remains with the integrating application. There is no USSD interface.

## Verify the complete stack

Build each service with `./mvnw clean verify`, then run `python3 scripts/pilot-e2e.py` from the repository root. It creates only randomly named isolated test databases/containers, ephemeral signing keys and synthetic customers. It launches the real executable services, injects a lost ledger acknowledgement, races withdrawals, verifies signed webhooks and restores every service database into a separate empty target. It restarts restored services and rechecks PIN verification, transfer status and balance. Existing local service ports and databases are not used. Requires Docker, OpenSSL and Java 25+. Failed-run logs stay in a temporary directory; the private signing key is removed in cleanup.

GitHub Actions verifies each service, assembles contracts and runs this scenario. Images publish only for an explicit `v0.1.*` tag after these checks. Configure the `pilot-release` environment and reviewed digest-pinned `JAVA_BUILD_IMAGE` / `JAVA_RUNTIME_IMAGE` repository variables first. Images are tagged by the exact Git commit SHA; no floating `latest` tag is published. Deployment is an explicit operator step, not a PR side effect.

## Deploy an isolated pilot

Copy `deploy/pilot.env.example` to a secret-managed file outside the checkout. Fill all issuer, database and operator-approved pricing/limits/risk settings. Use a reviewed digest-pinned PostgreSQL 17 image, the published image registry and exact commit tag. Supply unique database bootstrap/app passwords; app users are not PostgreSQL superusers. Supply a stable external PIN pepper and preserve its version securely across restores. Service credentials must be issued with the intended tenant, subject, audiences and least-privilege grants described in the feature docs.

Run `docker compose --env-file /secure/pilot.env -f compose.pilot.yaml config --quiet`, then `docker compose --env-file /secure/pilot.env -f compose.pilot.yaml up -d --wait`. Only the gateway is published, at `127.0.0.1:8080`; terminate TLS and configure an authenticated operator access path for internal APIs on the private application network. Application containers run as an unprivileged user with a read-only root filesystem and dropped capabilities. Do not expose PostgreSQL or internal financial routes publicly.

Enable transfer recovery and webhook dispatch explicitly after credentials and destinations are ready. Reopening a wallet requires a separate wallet service token map; operator authority is never relayed as a service identity. The public Postman examples are in `deploy/postman/pilot.json`; set bearerToken and IDs from your integration before sending. Base service contracts plus `services/*/contracts/*.json` are the source; assemble them with `scripts/CompileContracts.java` and the SnakeYAML jar bundled inside ledger-service's executable jar. Generated complete service contracts are written to `deploy/openapi`.

## Monitor

Expose Actuator `health,info,metrics` only on the internal network through `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`. Metrics require a signed service JWT with sub `metrics-collector`, the target audience, a valid provisioned integrator claim and `operations:metrics:read`. This global operational grant must never be assigned to an integrating application's customer-facing principal.

Run `scripts/metrics-collector.py` on that network with `BOODOO_MONITOR_ORIGINS_JSON` mapping service names to fixed internal origins and `BOODOO_MONITOR_TOKEN_FILE` pointing to an externally rotated JSON map of service bearer tokens. It rereads the secret file on every probe. The Prometheus endpoint binds to `127.0.0.1:9109/metrics` by default; change the bind address only inside a controlled monitoring network. Scrape it and load `deploy/alerts.yaml`. Alert timings are operational starting points for the pilot. Immutable historical reconciliation findings remain counted after investigation; use the report and investigation records to triage them instead of deleting evidence.

## Back up and restore

Quiesce ingress and workers, finish or record all uncertain financial intents, then back up **every** service database and preserve the issuer/PIN pepper/secret versions separately. Individual database dumps are transactionally consistent; separately timed live dumps do not constitute a consistent cross-service recovery point. Record one coordinated backup-set identity and timestamps before resuming writes. Encrypt dumps before off-host storage and test the complete set in isolation.

Use a PostgreSQL 17-compatible client. Set PGHOST/PGPORT/PGDATABASE/PGUSER and provide credentials through a protected passfile or environment. `scripts/backup-db.sh /secure/ledger.dump` refuses overwrites, uses restrictive file permissions and emits a SHA-256 sidecar. `RESTORE_TO_EMPTY_DATABASE=yes scripts/restore-db.sh /secure/ledger.dump` verifies the checksum and refuses a nonempty target. Provision target database ownership before restore; no `--clean` operation destroys an existing database. After restoring the complete set, verify row integrity, PIN key versions, cross-service references, journal balances, outstanding reservations and recovery queues before opening traffic. Restore testing in the full-stack scenario checks all service databases and reopens restored APIs.

## Pilot boundaries

Real-money deployment still requires the operator's approved tariff/limit/risk policies, issuer grants, external statement authenticity checks, regulatory/operational review and provider-specific settlement evidence. Cash receipt evidence in this pilot is asserted by one operator and reviewed by another; it is not fabricated as a bank attestation. Zero-fee cash must be explicitly enabled. Generic reversals deliberately exclude physical cash settlement journals: failed physical disbursement/return correction needs coordinated provider/cash evidence before real cash traffic. Expired uncertain cash reservations remain held for investigation. These external operational gates are not removed by creating or merging PRs.
