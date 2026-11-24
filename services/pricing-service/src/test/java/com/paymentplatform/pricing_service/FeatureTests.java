package com.paymentplatform.pricing_service;

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
        registry.add("boodoo.pricing.version",()->"test-v1");registry.add("boodoo.pricing.basis-points",()->100);registry.add("boodoo.pricing.flat-fee",()->1);registry.add("boodoo.pricing.ttl-seconds",()->300);
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-pricing-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE price_quotes");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-pricing-api")
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
    private String token() throws Exception{return service("partner-a","pricing:quotes:write pricing:quotes:read");}
    private String body(UUID ref,long amount){return json.writeValueAsString(Map.of("reference",ref,"amount",amount,"currency","XOF"));}
    @Test void quotesExplicitPolicyWithCeilingRoundingAndStableSnapshot() throws Exception {
        String request=body(UUID.randomUUID(),101);var response=post("/internal/v1/quotes",request,"same",token());assertThat(response.statusCode()).isEqualTo(201);
        var quote=json.readTree(response.body());assertThat(quote.get("fee").asLong()).isEqualTo(3);assertThat(quote.get("totalDebit").asLong()).isEqualTo(104);assertThat(quote.get("policyVersion").asString()).isEqualTo("test-v1");
        var replay=post("/internal/v1/quotes",request,"same",token());assertThat(replay.body()).isEqualTo(response.body());assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(get("/internal/v1/quotes/"+quote.get("id").asString(),token()).body()).isEqualTo(response.body());
    }
    @Test void rejectsOverflowInvalidAmountsAndConflictingKeys() throws Exception {
        UUID ref=UUID.randomUUID();post("/internal/v1/quotes",body(ref,100),"same",token());
        assertThat(post("/internal/v1/quotes",body(ref,101),"same",token()).statusCode()).isEqualTo(409);
        assertThat(post("/internal/v1/quotes",body(ref,100),"other",token()).statusCode()).isEqualTo(409);
        assertThat(post("/internal/v1/quotes",body(UUID.randomUUID(),Long.MAX_VALUE),"overflow",token()).statusCode()).isEqualTo(400);
        assertThat(post("/internal/v1/quotes",body(UUID.randomUUID(),0),"zero",token()).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_quotes",Integer.class)).isEqualTo(1);
    }
    @Test void isolatesTenantsAndScopesAndRequiresServiceIdentity() throws Exception {
        var response=post("/internal/v1/quotes",body(UUID.randomUUID(),100),"one",token());String id=json.readTree(response.body()).get("id").asString();
        assertThat(get("/internal/v1/quotes/"+id,service("partner-b","pricing:quotes:read")).statusCode()).isEqualTo(404);
        assertThat(post("/internal/v1/quotes",body(UUID.randomUUID(),100),"two",auth("partner-a","pricing:quotes:write","operator","operator")).statusCode()).isEqualTo(403);
        assertThat(post("/internal/v1/quotes",body(UUID.randomUUID(),100),"two",null).statusCode()).isEqualTo(401);
        assertThat(get("/internal/v1/quotes/"+id,service("partner-a","pricing:quotes:write")).statusCode()).isEqualTo(403);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("DELETE FROM price_quotes")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void concurrentRetriesCreateOneImmutableQuote() throws Exception {
        String body=body(UUID.randomUUID(),100),token=token();var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();return post("/internal/v1/quotes",body,"same",token).body();});var b=pool.submit(()->{start.await();return post("/internal/v1/quotes",body,"same",token).body();});start.countDown();assertThat(a.get()).isEqualTo(b.get());}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_quotes",Integer.class)).isEqualTo(1);
    }

}
