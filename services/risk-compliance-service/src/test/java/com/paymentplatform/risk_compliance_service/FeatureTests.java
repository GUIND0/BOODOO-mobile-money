package com.paymentplatform.risk_compliance_service;

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
        registry.add("boodoo.risk.version",()->"test-v1");registry.add("boodoo.risk.automatic-amount",()->100);registry.add("boodoo.risk.ttl-seconds",()->60);registry.add("boodoo.risk.blocked-customers",()->new UUID(0,1).toString());
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-risk-compliance-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE risk_evaluations");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-risk-compliance-api")
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
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private String token() throws Exception{return service("partner-a","risk:evaluations:write risk:evaluations:read");}
    private String body(UUID id,UUID customer,long amount){return json.writeValueAsString(Map.of("id",id,"reference",new UUID(0,99),"customerId",customer,"amount",amount,"currency","XOF"));}
    @Test void evaluatesExplicitLocalRulesAndPreservesTheDecisionOnReplay() throws Exception {
        UUID id=UUID.randomUUID(),customer=UUID.randomUUID();String body=body(id,customer,100);var result=post("/internal/v1/evaluations",body,null,token());assertThat(result.statusCode()).isEqualTo(201);
        assertThat(json.readTree(result.body()).get("outcome").asString()).isEqualTo("ALLOW");assertThat(post("/internal/v1/evaluations",body,null,token()).body()).isEqualTo(result.body());
        assertThat(get("/internal/v1/evaluations/"+id,token()).body()).isEqualTo(result.body());
        assertThat(json.readTree(post("/internal/v1/evaluations",body(UUID.randomUUID(),customer,101),null,token()).body()).get("outcome").asString()).isEqualTo("REVIEW");
        assertThat(json.readTree(post("/internal/v1/evaluations",body(UUID.randomUUID(),new UUID(0,1),1),null,token()).body()).get("outcome").asString()).isEqualTo("DENY");
    }
    @Test void rejectsConflictsAndDoesNotAllowUnknownOrForeignReads() throws Exception {
        UUID id=UUID.randomUUID(),customer=UUID.randomUUID();post("/internal/v1/evaluations",body(id,customer,100),null,token());
        assertThat(post("/internal/v1/evaluations",body(id,customer,101),null,token()).statusCode()).isEqualTo(409);
        assertThat(get("/internal/v1/evaluations/"+id,service("partner-b","risk:evaluations:read")).statusCode()).isEqualTo(404);
        assertThat(get("/internal/v1/evaluations/"+UUID.randomUUID(),token()).statusCode()).isEqualTo(404);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("DELETE FROM risk_evaluations")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void requiresTrustedServiceScopesAndValidAmounts() throws Exception {
        String body=body(UUID.randomUUID(),UUID.randomUUID(),10);
        assertThat(post("/internal/v1/evaluations",body,null,null).statusCode()).isEqualTo(401);
        assertThat(post("/internal/v1/evaluations",body,null,service("partner-a","risk:evaluations:read")).statusCode()).isEqualTo(403);
        assertThat(post("/internal/v1/evaluations",body,null,auth("partner-a","risk:evaluations:write","operator","operator")).statusCode()).isEqualTo(403);
        assertThat(post("/internal/v1/evaluations",body(UUID.randomUUID(),UUID.randomUUID(),0),null,token()).statusCode()).isEqualTo(400);
    }

}
