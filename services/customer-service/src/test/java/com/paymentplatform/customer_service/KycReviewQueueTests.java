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
class KycReviewQueueTests {
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
        jdbc.execute("TRUNCATE kyc_decision_requests, kyc_decisions, kyc_submission_requests, kyc_submissions, customer_profile_updates, customer_creation_requests, customers");
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
        return dossier(customer, tenant, number, status, "submitter");
    }
    private UUID dossier(UUID customer, String tenant, int number, String status, String actor) {
        UUID id = new UUID(0, number);
        jdbc.update("INSERT INTO kyc_submissions(id,integrator_id,customer_id,profile_version,profile_snapshot,documents,submitted_by) VALUES (?,?,?,0,'{}','[]',?)",
                id, tenant, customer, actor);
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
    private String reviewer() throws Exception { return auth("partner-a", "kyc:review", "reviewer", "operator"); }

    @Test void paginatesActivePartnerDossiersWithoutDisclosingProfileOrEvidence() throws Exception {
        UUID a = dossier(customer(1, "partner-a"), "partner-a", 101, "UNDER_REVIEW");
        dossier(customer(2, "partner-b"), "partner-b", 102, "UNDER_REVIEW");
        UUID b = dossier(customer(3, "partner-a"), "partner-a", 103, "UNDER_REVIEW");
        UUID c = dossier(customer(5, "partner-a"), "partner-a", 105, "UNDER_REVIEW");
        var first = get("/v1/kyc-review-queue?limit=2", reviewer());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        var page = json.readTree(first.body());
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("submissionId").asString()).isEqualTo(a.toString());
        assertThat(page.get("items").get(1).get("submissionId").asString()).isEqualTo(b.toString());
        assertThat(page.get("items").get(0).get("profileVersion").asLong()).isZero();
        assertThat(page.get("items").get(0).size()).isEqualTo(4);
        assertThat(page.get("nextAfter").asString()).isEqualTo(b.toString());
        var last = json.readTree(get("/v1/kyc-review-queue?limit=2&after=" + b, reviewer()).body());
        assertThat(last.get("items").size()).isEqualTo(1);
        assertThat(last.get("items").get(0).get("submissionId").asString()).isEqualTo(c.toString());
        assertThat(last.get("nextAfter").isNull()).isTrue();
        assertThat(first.body()).doesNotContain("firstName", "documents", "submittedBy", "profileSnapshot");
    }

    @Test void requiresSignedOperatorIdentityAndReviewScope() throws Exception {
        dossier(customer(1, "partner-a"), "partner-a", 101, "UNDER_REVIEW");
        assertThat(get("/v1/kyc-review-queue", null).statusCode()).isEqualTo(401);
        for (String token : List.of(auth("partner-a", "kyc:read", "reviewer", "operator"),
                auth("partner-a", "kyc:review", "reviewer", null),
                auth("partner-a", "kyc:review", null, "operator"),
                auth("partner-a", "kyc:review", " ", "operator"))) {
            assertThat(get("/v1/kyc-review-queue", token).statusCode()).isEqualTo(403);
        }
        assertThat(get("/v1/kyc-review-queue", reviewer()).statusCode()).isEqualTo(200);
    }

    @Test void excludesOwnSubmissionsFinalDecisionsStaleProfilesAndInactiveDossiers() throws Exception {
        dossier(customer(1, "partner-a"), "partner-a", 101, "UNDER_REVIEW", "reviewer");
        dossier(customer(2, "partner-a"), "partner-a", 102, "APPROVED");
        dossier(customer(3, "partner-a"), "partner-a", 103, "REJECTED");
        UUID staleCustomer = customer(4, "partner-a");
        dossier(staleCustomer, "partner-a", 104, "UNDER_REVIEW");
        jdbc.update("UPDATE customers SET profile_version=2 WHERE id=?", staleCustomer);
        UUID decidedCustomer = customer(5, "partner-a");
        UUID decided = dossier(decidedCustomer, "partner-a", 105, "UNDER_REVIEW");
        jdbc.update("INSERT INTO kyc_decisions(id,integrator_id,customer_id,submission_id,status,reason_code,reviewed_by) VALUES (?,'partner-a',?,?,'REJECTED','TEST','another-reviewer')",
                UUID.randomUUID(), decidedCustomer, decided);
        UUID resubmittedCustomer = customer(6, "partner-a");
        dossier(resubmittedCustomer, "partner-a", 106, "UNDER_REVIEW");
        UUID active = dossier(resubmittedCustomer, "partner-a", 107, "UNDER_REVIEW");
        customer(7, "partner-a");
        var page = json.readTree(get("/v1/kyc-review-queue?limit=1", reviewer()).body());
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("submissionId").asString()).isEqualTo(active.toString());
        assertThat(page.get("nextAfter").isNull()).isTrue();
    }

    @Test void liveQueueRemovesDecidedEntriesBetweenPagesWithoutRepeatingEarlierRows() throws Exception {
        UUID first = dossier(customer(1, "partner-a"), "partner-a", 101, "UNDER_REVIEW");
        UUID secondCustomer = customer(2, "partner-a");
        dossier(secondCustomer, "partner-a", 102, "UNDER_REVIEW");
        UUID third = dossier(customer(3, "partner-a"), "partner-a", 103, "UNDER_REVIEW");
        var page = json.readTree(get("/v1/kyc-review-queue?limit=1", reviewer()).body());
        assertThat(page.get("nextAfter").asString()).isEqualTo(first.toString());
        jdbc.update("UPDATE customers SET kyc_status='REJECTED',profile_version=2 WHERE id=?", secondCustomer);
        var next = json.readTree(get("/v1/kyc-review-queue?limit=1&after=" + first, reviewer()).body());
        assertThat(next.get("items").size()).isEqualTo(1);
        assertThat(next.get("items").get(0).get("submissionId").asString()).isEqualTo(third.toString());
        assertThat(next.get("nextAfter").isNull()).isTrue();
    }

    @Test void rejectsUnboundedLimitsAndMalformedCursors() throws Exception {
        for (String query : List.of("limit=0", "limit=101", "limit=-5", "limit=wrong", "after=invalid")) {
            var response = get("/v1/kyc-review-queue?" + query, reviewer());
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test void defaultsToFiftyRowsAndTreatsAnyCursorAsABoundaryWithinTheTenant() throws Exception {
        for (int n = 1; n <= 51; n++) dossier(customer(n, "partner-a"), "partner-a", n + 100, "UNDER_REVIEW");
        var page = json.readTree(get("/v1/kyc-review-queue", reviewer()).body());
        assertThat(page.get("items").size()).isEqualTo(50);
        assertThat(page.get("nextAfter").asString()).isEqualTo(new UUID(0, 150).toString());
        UUID foreign = dossier(customer(52, "partner-b"), "partner-b", 152, "UNDER_REVIEW");
        var empty = json.readTree(get("/v1/kyc-review-queue?after=" + foreign, reviewer()).body());
        assertThat(empty.get("items").size()).isZero();
        assertThat(empty.get("nextAfter").isNull()).isTrue();
    }

    @Test void storageFailureReturnsSanitizedServiceUnavailable() throws Exception {
        String token = reviewer();
        jdbc.execute("ALTER TABLE kyc_submissions RENAME TO submissions_temporarily_unavailable");
        try {
            var response = get("/v1/kyc-review-queue", token);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("CUSTOMER_OPERATION_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("SELECT", "SQLException", "submissions_temporarily_unavailable");
        } finally { jdbc.execute("ALTER TABLE submissions_temporarily_unavailable RENAME TO kyc_submissions"); }
    }

}
