package com.paymentplatform.audit_service;

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
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-audit-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE audit_events");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-audit-api")
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
    private String token() throws Exception{return service("partner-a","audit:events:write audit:events:read");}
    private String body(UUID id,UUID resource,String reason){return json.writeValueAsString(Map.of("id",id,"resourceId",resource,"type","TRANSFER_POSTED","reasonCode",reason));}
    @Test void appendsImmutableProducerBoundEventsAndPagesByResource() throws Exception {
        UUID resource=UUID.randomUUID(),first=new UUID(0,1),second=new UUID(0,2);String token=token(),body=body(first,resource,"POSTED");
        var response=post("/internal/v1/events",body,null,token);assertThat(response.statusCode()).isEqualTo(201);assertThat(json.readTree(response.body()).get("producer").asString()).isEqualTo("integration-service");
        assertThat(post("/internal/v1/events",body,null,token).body()).isEqualTo(response.body());post("/internal/v1/events",body(second,resource,"POSTED"),null,token);
        var page=json.readTree(get("/internal/v1/events?resourceId="+resource+"&limit=1",token).body());assertThat(page.get("items").size()).isEqualTo(1);assertThat(page.get("nextAfter").asString()).isEqualTo(first.toString());
        var last=json.readTree(get("/internal/v1/events?resourceId="+resource+"&after="+first,token).body());assertThat(last.get("items").size()).isEqualTo(1);assertThat(last.get("nextAfter").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("DELETE FROM audit_events")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void rejectsConflictingProducerOrPayloadAndPrivateDataFields() throws Exception {
        UUID id=UUID.randomUUID(),resource=UUID.randomUUID();String token=token();post("/internal/v1/events",body(id,resource,"POSTED"),null,token);
        assertThat(post("/internal/v1/events",body(id,resource,"OTHER"),null,token).statusCode()).isEqualTo(409);
        assertThat(post("/internal/v1/events",body(id,resource,"POSTED"),null,auth("partner-a","audit:events:write","other","service")).statusCode()).isEqualTo(409);
        assertThat(post("/internal/v1/events",body(UUID.randomUUID(),resource,"has spaces"),null,token).statusCode()).isEqualTo(400);
        assertThat(post("/internal/v1/events","{\"pin\":\"123456\"}",null,token).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events",Integer.class)).isEqualTo(1);
    }
    @Test void requiresScopedServiceIdentityAndReturnsOnlyOwnTenantData() throws Exception {
        UUID resource=UUID.randomUUID();post("/internal/v1/events",body(UUID.randomUUID(),resource,"POSTED"),null,token());
        assertThat(json.readTree(get("/internal/v1/events?resourceId="+resource,service("partner-b","audit:events:read")).body()).get("items").size()).isZero();
        assertThat(get("/internal/v1/events?resourceId="+resource,service("partner-a","audit:events:write")).statusCode()).isEqualTo(403);
        assertThat(post("/internal/v1/events",body(UUID.randomUUID(),resource,"POSTED"),null,auth("partner-a","audit:events:write","operator","operator")).statusCode()).isEqualTo(403);
        assertThat(get("/internal/v1/events?resourceId="+resource,null).statusCode()).isEqualTo(401);
        assertThat(get("/internal/v1/events",token()).statusCode()).isEqualTo(400);assertThat(get("/internal/v1/events?resourceId="+resource+"&limit=101",token()).statusCode()).isEqualTo(400);
    }

}
