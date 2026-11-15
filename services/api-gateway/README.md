# API Gateway

API Gateway will own the controlled entry point for BOODOO client and operator applications, using Spring Cloud Gateway Server Web MVC.

This PR initializes the application only. It does not implement business routes, identity-provider integration, rate-limit storage, CORS policy, and downstream clients.

## Requirements

- JDK 25 or later
- Maven is downloaded by the included wrapper; Docker and PostgreSQL are not required

## Run locally

From `services/api-gateway`:

```sh
./mvnw spring-boot:run
```

The application listens on port `8080`, overridden with `API_GATEWAY_SERVER_PORT`. No database, Compose stack, upstream routes, or provider credentials are configured.

Spring Cloud `2025.1.4` is managed through its release-train BOM, with the Gateway Server Web MVC starter. Compatibility follows the [Spring Cloud matrix](https://spring.io/projects/spring-cloud/) and the [Gateway starter documentation](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/starter.html). The compatibility verifier remains enabled.

```sh
curl --fail http://localhost:8080/actuator/health
```

## Verify

```sh
./mvnw verify
```

Tests start the real HTTP application on a random port, check health, and verify that unconfigured business and management endpoints reject unauthenticated requests without creating sessions. No backend service is contacted.

## Initial access policy

Only `/actuator/health` is public, with component details hidden. All other requests are denied. Form login, HTTP Basic, logout, request caching, generated development users, and server-side session creation are disabled. CSRF protection retains Spring Security defaults. Authentication mechanisms and explicit permissions must be added with the business workflows.

## Boundary and next implementation

- Define an explicit allowlist of public routes and fixed backend destinations; do not accept client-supplied upstream URLs or proxy arbitrary hosts.
- Integrate trusted token validation with configured issuer, audience, expiry, and key rotation. Identity Service remains responsible for authentication workflows.
- Authorize routes by actor and operation, while retaining ownership and business authorization checks inside each downstream service.
- Define request-size limits, timeouts, per-caller rate limits, CORS policy, and handling of forwarded headers for the chosen deployment topology.
- Remove untrusted identity and forwarding headers and propagate only validated caller context and correlation identifiers.
- Do not automatically retry financial writes. Preserve idempotency keys and return uncertain-outcome information according to downstream contracts.
- Keep health and operational management exposure separate from public business routes; exclude credentials and personal data from access logs.
- Keep business rules, payment state, customer records, and financial databases in their owning services. No gateway database is introduced.

No business endpoints, real credentials, external integrations, or cross-service operations are enabled by this initialization.
