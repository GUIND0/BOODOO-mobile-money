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
class KycLifecycleTests {
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


 private HttpResponse<String> change(UUID id,String event,String action,long version,String token)throws Exception{
  String body=json.writeValueAsString(Map.of("id",event,"action",action,"expectedVersion",version,"reasonCode","EVIDENCE_REVIEW"));
  return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/customers/"+id+"/kyc-lifecycle")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void revocationInvalidatesApprovalPreservesDossierAndReplaysOriginalResult()throws Exception{
  UUID customer=customer(1,"partner-a"),submission=dossier(customer,"partner-a",100,"APPROVED");String event=UUID.randomUUID().toString(),token=auth("partner-a","kyc:lifecycle","reviewer","operator");
  assertThat(change(customer,event,"REVOKE",1,auth("partner-a","kyc:lifecycle","service","service")).statusCode()).isEqualTo(403);
  assertThat(change(customer,event,"REVOKE",1,auth("partner-b","kyc:lifecycle","reviewer","operator")).statusCode()).isEqualTo(404);
  assertThat(change(customer,event,"REVOKE",0,token).statusCode()).isEqualTo(412);
  var first=change(customer,event,"REVOKE",1,token);assertThat(first.statusCode()).isEqualTo(200);assertThat(change(customer,event,"REVOKE",1,token).body()).isEqualTo(first.body());
  assertThat(change(customer,event,"RENEW",1,token).statusCode()).isEqualTo(409);
  assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers WHERE id=?",String.class,customer)).isEqualTo("PENDING");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_submissions WHERE id=?",Integer.class,submission)).isEqualTo(1);
  var approval=get("/internal/v1/customers/"+customer+"/kyc-approval",auth("partner-a","customers:kyc:read","wallet-service","service"));assertThat(approval.statusCode()).isEqualTo(200);assertThat(json.readTree(approval.body()).get("approvalCurrent").asBoolean()).isFalse();
 }
}
