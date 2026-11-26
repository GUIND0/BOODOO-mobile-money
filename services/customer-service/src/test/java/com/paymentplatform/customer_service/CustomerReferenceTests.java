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
class CustomerReferenceTests {
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
    @Test void findsLiveProfileAndEtagWithinTheAuthenticatedTenant() throws Exception {
        UUID id = customer(1, "partner-a"); customer(2, "partner-b");
        jdbc.update("UPDATE customers SET external_reference='ref-1' WHERE integrator_id='partner-b'");
        jdbc.update("UPDATE customers SET first_name='Updated',profile_version=4 WHERE id=?", id);
        var response = get("/v1/customers/by-reference?externalReference=%20ref-1%20", auth("partner-a", "customers:read", "client", null));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("ETag")).contains("\"4\"");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(response.body()).get("firstName").asString()).isEqualTo("Updated");
        assertThat(json.readTree(response.body()).get("id").asString()).isEqualTo(id.toString());
    }
    @Test void doesNotLeakForeignReferencesOrTreatThemAsSql() throws Exception {
        customer(1, "partner-b");
        String token = auth("partner-a", "customers:read", "client", null);
        for (String ref : List.of("ref-1", "missing", "%27%20OR%201%3D1--")) {
            var response = get("/v1/customers/by-reference?externalReference=" + ref, token);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("CUSTOMER_NOT_FOUND");
            assertThat(response.body()).doesNotContain("Awa");
        }
    }
    @Test void validatesReferenceAndRequiresReadPermission() throws Exception {
        String token = auth("partner-a", "customers:read", "client", null);
        for (String query : List.of("", "?externalReference=", "?externalReference=%20", "?externalReference=" + "x".repeat(129)))
            assertThat(get("/v1/customers/by-reference" + query, token).statusCode()).isEqualTo(400);
        assertThat(get("/v1/customers/by-reference?externalReference=ref-1", null).statusCode()).isEqualTo(401);
        assertThat(get("/v1/customers/by-reference?externalReference=ref-1", auth("partner-a", "customers:write", "client", null)).statusCode()).isEqualTo(403);
    }

}
