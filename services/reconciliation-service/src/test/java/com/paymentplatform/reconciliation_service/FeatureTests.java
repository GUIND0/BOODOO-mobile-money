package com.paymentplatform.reconciliation_service;

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
class FeatureTests {
    private static final java.util.concurrent.atomic.AtomicInteger MODE=new java.util.concurrent.atomic.AtomicInteger(),CALLS=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> FORWARDED=new java.util.concurrent.atomic.AtomicReference<>();
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
        registry.add("boodoo.dependencies.origins.ledger",()->"http://127.0.0.1:"+KEYS.getAddress().getPort());
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-reconciliation-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE statement_investigations,external_statements,reconciliation_reports");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-reconciliation-api")
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
    private String service(String tenant,String scopes) throws Exception {return auth(tenant,scopes,"integration-service","service");}
    private HttpResponse<String> post(String path,String body,String key,String token) throws Exception {return send("POST",path,body,key,token);}
    private HttpResponse<String> send(String method,String path,String body,String key,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json").header("X-Integrator-Id","partner-b").method(method,HttpRequest.BodyPublishers.ofString(body));
        if(key!=null)request.header("Idempotency-Key",key);if(token!=null)request.header("Authorization","Bearer "+token);
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
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
            server.createContext("/internal/v1/reconciliation/snapshot",exchange->{
                CALLS.incrementAndGet();FORWARDED.set(exchange.getRequestHeaders().getFirst("Authorization"));int mode=MODE.get();
                String value=mode==3?"x".repeat(70000):"{\"journalCount\":2,\"unbalancedJournals\":"+(mode==1?1:0)+",\"invalidBalances\":0,\"checkedAt\":\""+(mode==4?Instant.EPOCH:Instant.now())+"\"}";
                byte[] snapshotBytes=value.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(mode==2?503:200,snapshotBytes.length);try(var out=exchange.getResponseBody()){out.write(snapshotBytes);}
            });
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private String token() throws Exception{return service("partner-a","reconciliation:checks:write reconciliation:checks:read");}
    @BeforeEach void resetPeer(){MODE.set(0);CALLS.set(0);}
    @Test void freezesIntegrityReportAndDoesNotRecheckTheLedgerOnReplay() throws Exception {
        var first=post("/internal/v1/reconciliations","","same",token());assertThat(first.statusCode()).isEqualTo(201);var report=json.readTree(first.body());assertThat(report.get("status").asString()).isEqualTo("CONSISTENT");assertThat(report.get("journalCount").asLong()).isEqualTo(2);
        assertThat(post("/internal/v1/reconciliations","","same",token()).body()).isEqualTo(first.body());assertThat(CALLS.get()).isEqualTo(1);
        assertThat(get("/internal/v1/reconciliations/"+report.get("id").asString(),token()).body()).isEqualTo(first.body());
        MODE.set(1);var discrepancy=post("/internal/v1/reconciliations","","another",token());assertThat(json.readTree(discrepancy.body()).get("status").asString()).isEqualTo("DISCREPANCY");
        assertThat(FORWARDED.get()).isNotBlank();
    }
    @Test void dependencyErrorsMalformedAndStaleResponsesNeverBecomeConsistentReports() throws Exception {
        for(int mode:List.of(2,3,4)){MODE.set(mode);assertThat(post("/internal/v1/reconciliations","","failure-"+mode,token()).statusCode()).isEqualTo(503);}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_reports",Integer.class)).isZero();
    }
    @Test void isolatesTenantsAndChecksServiceScopesAndImmutableHistory() throws Exception {
        var first=post("/internal/v1/reconciliations","","same",token());String id=json.readTree(first.body()).get("id").asString();
        assertThat(get("/internal/v1/reconciliations/"+id,service("partner-b","reconciliation:checks:read")).statusCode()).isEqualTo(404);
        assertThat(post("/internal/v1/reconciliations","","another",service("partner-a","reconciliation:checks:read")).statusCode()).isEqualTo(403);
        assertThat(post("/internal/v1/reconciliations","","another",null).statusCode()).isEqualTo(401);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("DELETE FROM reconciliation_reports")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

}
