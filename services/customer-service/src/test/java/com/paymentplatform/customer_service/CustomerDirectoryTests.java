package com.paymentplatform.customer_service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
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
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerDirectoryTests {
    private static final String ISSUER = "https://directory-test.example";
    private static final RSAKey KEY = signingKey();
    private static final HttpServer KEYS = keys();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-customer-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE kyc_decision_requests, kyc_decisions, kyc_submission_requests, kyc_submissions, customer_profile_updates, customer_creation_requests, customers CASCADE");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-customer-api")
                .claim("integrator_id", tenant).claim("scope", scope).expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (actor != null) claims.subject(actor);
        if (role != null) claims.claim("actor_type", role);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET()
                .header("X-Integrator-Id", "partner-b").header("X-Actor-Type", "operator");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private UUID customer(int number, String tenant) {
        UUID id = new UUID(0, number);
        jdbc.update("INSERT INTO customers(id,integrator_id,external_reference,first_name,last_name,phone_number) VALUES (?,?,?,'Awa','Test',?)",
                id, tenant, "ref-" + number, "+2237" + String.format("%07d", number));
        return id;
    }
    private UUID dossier(UUID customer, String tenant, int number, String status) {
        UUID id = new UUID(0, number);
        jdbc.update("INSERT INTO kyc_submissions(id,integrator_id,customer_id,profile_version,profile_snapshot,documents,submitted_by) VALUES (?,?,?,0,'{}','[]','submitter')",
                id, tenant, customer);
        jdbc.update("UPDATE customers SET active_kyc_submission_id=?,kyc_status=?,profile_version=1 WHERE id=?", id, status, customer);
        return id;
    }
    private static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("test").generate(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static HttpServer keys() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @Test void paginatesWithoutDuplicatesAndNeverLeaksAnotherPartner() throws Exception {
        UUID a = customer(1, "partner-a"), b = customer(3, "partner-a"), c = customer(5, "partner-a");
        customer(2, "partner-b");
        String token = auth("partner-a", "customers:read", "client", null);
        var first = get("/v1/customers?limit=2", token);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        var page = json.readTree(first.body());
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(a.toString());
        assertThat(page.get("items").get(1).get("id").asString()).isEqualTo(b.toString());
        assertThat(page.get("nextAfter").asString()).isEqualTo(b.toString());
        var last = json.readTree(get("/v1/customers?limit=2&after=" + b, token).body());
        assertThat(last.get("items").size()).isEqualTo(1);
        assertThat(last.get("items").get(0).get("id").asString()).isEqualTo(c.toString());
        assertThat(last.get("nextAfter").isNull()).isTrue();
        assertThat(first.body()).doesNotContain("phoneNumber", "addressLine", "documents", "profile", "ref-2");
        assertThat(json.readTree(get("/v1/customers?after=" + c, token).body()).get("items").size()).isZero();
    }

    @Test void filtersAllKycStatesBeforeApplyingThePageLimit() throws Exception {
        customer(1, "partner-a");
        for (int n = 2; n <= 4; n++) dossier(customer(n, "partner-a"), "partner-a", n + 100,
                List.of("UNDER_REVIEW", "APPROVED", "REJECTED").get(n - 2));
        String token = auth("partner-a", "customers:read", "client", null);
        for (String status : List.of("PENDING", "UNDER_REVIEW", "APPROVED", "REJECTED")) {
            var response = get("/v1/customers?limit=1&kycStatus=" + status, token);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json.readTree(response.body());
            assertThat(body.get("items").size()).isEqualTo(1);
            assertThat(body.get("items").get(0).get("kycStatus").asString()).isEqualTo(status);
            assertThat(body.get("nextAfter").isNull()).isTrue();
        }
    }

    @Test void requiresReadAuthorityAndReturnsAnEmptyPageForAnEmptyTenant() throws Exception {
        customer(1, "partner-b");
        assertThat(get("/v1/customers", null).statusCode()).isEqualTo(401);
        assertThat(get("/v1/customers", auth("partner-a", "customers:write", "client", null)).statusCode()).isEqualTo(403);
        var result = get("/v1/customers", auth("partner-a", "customers:read", "client", null));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json.readTree(result.body()).get("items").size()).isZero();
        assertThat(json.readTree(result.body()).get("nextAfter").isNull()).isTrue();
    }

    @Test void rejectsUnboundedLimitsAndMalformedFilters() throws Exception {
        String token = auth("partner-a", "customers:read", "client", null);
        for (String query : List.of("limit=0", "limit=101", "limit=-1", "limit=abc", "after=invalid", "kycStatus=unknown")) {
            var response = get("/v1/customers?" + query, token);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test void defaultsToFiftyRowsAndHandlesForeignOrUnknownCursorsAsBoundaries() throws Exception {
        for (int n = 1; n <= 51; n++) customer(n, "partner-a");
        String token = auth("partner-a", "customers:read", "client", null);
        var page = json.readTree(get("/v1/customers", token).body());
        assertThat(page.get("items").size()).isEqualTo(50);
        assertThat(page.get("nextAfter").asString()).isEqualTo(new UUID(0, 50).toString());
        UUID foreign = customer(52, "partner-b");
        assertThat(json.readTree(get("/v1/customers?after=" + foreign, token).body()).get("items").size()).isZero();
        assertThat(get("/v1/customers?after=" + new UUID(0, 999), token).statusCode()).isEqualTo(200);
    }

    @Test void storageFailureReturnsSanitizedServiceUnavailable() throws Exception {
        String token = auth("partner-a", "customers:read", "client", null);
        jdbc.execute("ALTER TABLE customers RENAME TO customers_temporarily_unavailable");
        try {
            var response = get("/v1/customers", token);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("CUSTOMER_OPERATION_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("SELECT", "SQLException", "customers_temporarily_unavailable");
        } finally { jdbc.execute("ALTER TABLE customers_temporarily_unavailable RENAME TO customers"); }
    }

}
