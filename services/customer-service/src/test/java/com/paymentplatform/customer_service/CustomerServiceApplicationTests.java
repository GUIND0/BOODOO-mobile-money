package com.paymentplatform.customer_service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerServiceApplicationTests {
    private static final String ISSUER = "https://test-issuer.example";
    private static final String AUDIENCE = "boodoo-customer-api";
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final HttpServer KEYS = keyServer();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PROFILE = """
            {"externalReference":"user-001","firstName":"Awa","lastName":"Test","phoneNumber":"+22370000001"}
            """;
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> AUDIENCE);
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }

    @BeforeEach
    void clearCustomers() {
        jdbc.update("DELETE FROM customer_creation_requests");
        jdbc.update("DELETE FROM customers");
    }

    @AfterAll
    static void closeFixtures() { HTTP.close(); KEYS.stop(0); }

    @Test
    void startsWithMigratedPostgresAndReportsHealthy() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
        var response = HTTP.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        assertThat(json.readTree(response.body()).has("components")).isFalse();
    }

    @Test
    void createsPendingCustomerForAuthenticatedIntegrator() throws Exception {
        var response = post(PROFILE, "create-1", token("partner-a"));
        assertThat(response.statusCode()).isEqualTo(201);
        var body = json.readTree(response.body());
        assertThat(UUID.fromString(body.get("id").asString())).isNotNull();
        assertThat(Instant.parse(body.get("createdAt").asString())).isNotNull();
        assertThat(body.get("externalReference").asString()).isEqualTo("user-001");
        assertThat(body.get("firstName").asString()).isEqualTo("Awa");
        assertThat(body.get("phoneNumber").asString()).isEqualTo("+22370000001");
        assertThat(body.get("kycStatus").asString()).isEqualTo("PENDING");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT integrator_id FROM customers", String.class)).isEqualTo("partner-a");
        assertCounts(1);
    }

    @Test
    void replaysCanonicalRequestAndOriginalSnapshot() throws Exception {
        String auth = token("partner-a");
        var original = post(PROFILE, "stable-key", auth);
        String reordered = """
                {"phoneNumber":" +22370000001 ","lastName":"Test","firstName":" Awa ","externalReference":"user-001"}
                """;
        jdbc.update("UPDATE customers SET first_name = 'Updated' WHERE integrator_id = 'partner-a'");
        var replay = post(reordered, "stable-key", auth);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertCounts(1);
    }

    @Test
    void rejectsKeyReuseWithDifferentPayloadAndPreservesOriginal() throws Exception {
        String auth = token("partner-a");
        var original = post(PROFILE, "stable-key", auth);
        var conflict = post(PROFILE.replace("Awa", "Mariam"), "stable-key", auth);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(post(PROFILE, "stable-key", auth).body()).isEqualTo(original.body());
        assertCounts(1);
    }

    @Test
    void preventsDuplicateExternalReferenceAndPhoneWithinIntegrator() throws Exception {
        String auth = token("partner-a");
        assertThat(post(PROFILE, "first", auth).statusCode()).isEqualTo(201);
        for (String duplicate : List.of(PROFILE.replace("+22370000001", "+22370000002"), PROFILE.replace("user-001", "user-002"))) {
            var response = post(duplicate, UUID.randomUUID().toString(), auth);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("CUSTOMER_ALREADY_EXISTS");
        }
        assertCounts(1);
    }

    @Test
    void separatesPartnerNamespacesForReferencesPhonesAndKeys() throws Exception {
        var first = post(PROFILE, "same-key", token("partner-a"));
        var second = post(PROFILE, "same-key", token("partner-b"));
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(json.readTree(first.body()).get("id").asString()).isNotEqualTo(json.readTree(second.body()).get("id").asString());
        assertCounts(2);
        assertThat(post(PROFILE, "same-key", token("partner-a")).body()).isEqualTo(first.body());
        assertThat(post(PROFILE, "same-key", token("partner-b")).body()).isEqualTo(second.body());
    }

    @Test
    void ignoresSpoofedPartnerHeaders() throws Exception {
        var request = request(PROFILE, "spoof", token("partner-a"))
                .header("X-Integrator-Id", "partner-b").header("X-Tenant-Id", "partner-b").build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT integrator_id FROM customers", String.class)).isEqualTo("partner-a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{malformed", "{\"externalReference\":null}"})
    void rejectsIncompleteOrMalformedBody(String profile) throws Exception {
        assertThat(post(profile, "invalid", token("partner-a")).statusCode()).isEqualTo(400);
        assertCounts(0);
    }

    @Test
    void rejectsUntrustedBodyFields() throws Exception {
        for (String extra : List.of("\"kycStatus\":\"APPROVED\"", "\"integratorId\":\"partner-b\"", "\"phoneVerified\":true", "\"pin\":\"123456\"")) {
            String profile = PROFILE.strip().replace("}", "," + extra + "}");
            var response = post(profile, "untrusted", token("partner-a"));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("+22370000001", "123456");
        }
        assertCounts(0);
    }

    @Test
    void validatesProfileLengthsRequiredNamesAndPhoneFormat() throws Exception {
        for (String profile : List.of(PROFILE.replace("Awa", " "), PROFILE.replace("Test", " "),
                PROFILE.replace("user-001", " "), PROFILE.replace("Awa", "a".repeat(101)),
                PROFILE.replace("Test", "b".repeat(101)), PROFILE.replace("user-001", "c".repeat(129)),
                PROFILE.replace("+22370000001", "70000001"), PROFILE.replace("+22370000001", "+1234567890123456"))) {
            assertThat(post(profile, "invalid-profile", token("partner-a")).statusCode()).isEqualTo(400);
        }
        assertCounts(0);
    }

    @Test
    void requiresValidIdempotencyHeader() throws Exception {
        for (String key : List.of("", "bad key", "k".repeat(129), "invalid/key")) {
            assertThat(post(PROFILE, key, token("partner-a")).statusCode()).isEqualTo(400);
        }
        assertThat(post(PROFILE, null, token("partner-a")).statusCode()).isEqualTo(400);
        assertCounts(0);
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        var request = HttpRequest.newBuilder(uri("/v1/customers"))
                .header("Authorization", "Bearer " + token("partner-a"))
                .header("Idempotency-Key", "wrong-type")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(PROFILE)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(415);
        assertCounts(0);
    }

    @Test
    void requiresAuthenticationAndCreationScope() throws Exception {
        assertThat(post(PROFILE, "unauthenticated", null).statusCode()).isEqualTo(401);
        String readOnly = signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:read", Instant.now().plusSeconds(300));
        assertThat(post(PROFILE, "read-only", readOnly).statusCode()).isEqualTo(403);
        assertCounts(0);
    }

    @Test
    void validatesSignatureIssuerAudienceExpiryAndPartnerClaim() throws Exception {
        for (String invalid : List.of(
                signedToken(signingKey(), ISSUER, AUDIENCE, "partner-a", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, "https://wrong-issuer.example", AUDIENCE, "partner-a", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, "another-api", "partner-a", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:write", Instant.now().minusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, null, "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, List.of("partner-a"), "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "invalid/partner", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:write", null))) {
            assertThat(post(PROFILE, "invalid-token", invalid).statusCode()).isEqualTo(401);
        }
        assertCounts(0);
    }

    @Test
    void deniesOtherRoutes() throws Exception {
        for (String path : List.of("/actuator/info", "/v1/customers", "/v1/customers/unknown")) {
            var response = HTTP.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token("partner-a"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
    }

    @Test
    void concurrentRetriesProduceOneCustomerAndOneSnapshot() throws Exception {
        String auth = token("partner-a");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { start.await(); return post(PROFILE, "concurrent", auth); }));
            start.countDown();
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(20, TimeUnit.SECONDS));
            assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
            assertThat(responses.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
            assertThat(responses.stream().filter(r -> r.headers().firstValue("Idempotency-Replayed").orElse("").equals("false")).count()).isEqualTo(1);
            assertCounts(1);
        }
    }

    @Test
    void concurrentDuplicateProfilesWithDifferentKeysDoNotCreateDuplicates() throws Exception {
        String auth = token("partner-a");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return post(PROFILE, "duplicate-a", auth); });
            var second = executor.submit(() -> { start.await(); return post(PROFILE, "duplicate-b", auth); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(), second.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
            assertCounts(1);
        }
    }

    @Test
    void rollsBackCustomerIfSnapshotFailsAndAllowsRetry() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION reject_test_creation() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.idempotency_key = 'rollback-test' THEN
                        RAISE EXCEPTION 'Injected test failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER reject_test_creation BEFORE INSERT ON customer_creation_requests FOR EACH ROW EXECUTE FUNCTION reject_test_creation()");
        String auth = token("partner-a");
        try {
            var response = post(PROFILE, "rollback-test", auth);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("Injected test failure", "+22370000001");
            assertCounts(0);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_creation ON customer_creation_requests");
            jdbc.execute("DROP FUNCTION reject_test_creation()");
        }
        assertThat(post(PROFILE, "rollback-test", auth).statusCode()).isEqualTo(201);
        assertCounts(1);
    }

    @Test
    void confirmsOwnershipWithoutDisclosingProfileAndKeepsKycPending() throws Exception {
        var created = post(PROFILE, "ownership", token("partner-a"));
        UUID customer = UUID.fromString(json.readTree(created.body()).get("id").asString());
        String allowed = signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:ownership:read", Instant.now().plusSeconds(300));
        var response = ownership(customer, allowed);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(ownership(UUID.randomUUID(), allowed).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers", String.class)).isEqualTo("PENDING");
    }

    @Test
    void ownershipLookupDoesNotRevealAnotherIntegratorsCustomer() throws Exception {
        var created = post(PROFILE, "ownership", token("partner-a"));
        UUID customer = UUID.fromString(json.readTree(created.body()).get("id").asString());
        String foreign = signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-b", "customers:ownership:read", Instant.now().plusSeconds(300));
        assertThat(ownership(customer, foreign).statusCode()).isEqualTo(404);
        assertThat(ownership(customer, foreign).body()).isEmpty();
    }

    @Test
    void ownershipLookupRequiresItsOwnScope() throws Exception {
        assertThat(ownership(UUID.randomUUID(), null).statusCode()).isEqualTo(401);
        assertThat(ownership(UUID.randomUUID(), token("partner-a")).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> ownership(UUID customer, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri("/internal/v1/customers/" + customer + "/ownership")).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readsCurrentProfileWithReadScopeAndNoCache() throws Exception {
        var created = post(PROFILE, "read", token("partner-a"));
        UUID id = UUID.fromString(json.readTree(created.body()).get("id").asString());
        var read = readProfile(id, scopedToken("partner-a", "customers:read"));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(created.body());
        assertThat(read.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(read.headers().firstValue("Set-Cookie")).isEmpty();
        jdbc.update("UPDATE customers SET first_name = 'Changed' WHERE id = ?", id);
        assertThat(json.readTree(readProfile(id, scopedToken("partner-a", "customers:read")).body()).get("firstName").asString()).isEqualTo("Changed");
        assertThat(post(PROFILE, "read", token("partner-a")).body()).isEqualTo(created.body());
    }

    @Test
    void profileReadDoesNotRevealForeignOrMissingCustomers() throws Exception {
        UUID id = UUID.fromString(json.readTree(post(PROFILE, "read", token("partner-a")).body()).get("id").asString());
        var foreign = readProfile(id, scopedToken("partner-b", "customers:read"));
        var missing = readProfile(UUID.randomUUID(), scopedToken("partner-b", "customers:read"));
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(json.readTree(foreign.body()).get("code").asString()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(json.readTree(missing.body()).get("code").asString()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(foreign.body()).doesNotContain("Awa", "+22370000001", "user-001");
    }

    @Test
    void profileReadRequiresAuthenticationItsOwnScopeAndAValidUuid() throws Exception {
        assertThat(readProfile(UUID.randomUUID(), null).statusCode()).isEqualTo(401);
        assertThat(readProfile(UUID.randomUUID(), token("partner-a")).statusCode()).isEqualTo(403);
        var request = HttpRequest.newBuilder(uri("/v1/customers/not-a-uuid"))
                .header("Authorization", "Bearer " + scopedToken("partner-a", "customers:read")).GET().build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    private String scopedToken(String partner, String scopes) throws Exception {
        return signedToken(SIGNING_KEY, ISSUER, AUDIENCE, partner, scopes, Instant.now().plusSeconds(300));
    }

    private HttpResponse<String> readProfile(UUID customer, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/v1/customers/" + customer)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertCounts(int expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_creation_requests", Integer.class)).isEqualTo(expected);
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private HttpRequest.Builder request(String body, String key, String token) {
        var builder = HttpRequest.newBuilder(uri("/v1/customers")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }
    private HttpResponse<String> post(String body, String key, String token) throws Exception {
        return HTTP.send(request(body, key, token).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token(String integrator) throws Exception {
        return signedToken(SIGNING_KEY, ISSUER, AUDIENCE, integrator, "customers:write", Instant.now().plusSeconds(300));
    }
    private static String signedToken(RSAKey key, String issuer, String audience, Object integrator, String scope, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("integration-client")
                .claim("scope", scope).issueTime(new Date());
        if (integrator != null) claims.claim("integrator_id", integrator);
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
    private static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("test-key").generate(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static HttpServer keyServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                try (var output = exchange.getResponseBody()) { output.write(keys); }
            });
            server.start(); return server;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
