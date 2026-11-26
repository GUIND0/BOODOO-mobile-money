package com.paymentplatform.limits_service;

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
        registry.add("boodoo.limits.version",()->"test-v1");registry.add("boodoo.limits.per-transaction",()->100);registry.add("boodoo.limits.daily",()->100);registry.add("boodoo.limits.monthly",()->200);registry.add("boodoo.limits.ttl-seconds",()->300);
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-limits-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE limit_cancellations,limit_reservations");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-limits-api")
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
    private String token() throws Exception{return service("partner-a","limits:reservations:write limits:reservations:read limits:reservations:settle");}
    private String body(UUID ref,UUID customer,long amount){return json.writeValueAsString(Map.of("reference",ref,"customerId",customer,"amount",amount,"currency","XOF"));}
    @Test void reservesConfirmsAndReplaysTheOriginalSnapshotWithoutDoubleCounting() throws Exception {
        String body=body(UUID.randomUUID(),UUID.randomUUID(),80),token=token();var response=post("/internal/v1/reservations",body,"same",token);assertThat(response.statusCode()).isEqualTo(201);
        String id=json.readTree(response.body()).get("id").asString();assertThat(post("/internal/v1/reservations/"+id+"/confirm","",null,token).statusCode()).isEqualTo(200);
        assertThat(json.readTree(get("/internal/v1/reservations/"+id,token).body()).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(post("/internal/v1/reservations",body,"same",token).body()).isEqualTo(response.body());
        assertThat(post("/internal/v1/reservations/"+id+"/release","",null,token).statusCode()).isEqualTo(409);
    }
    @Test void concurrentReservationsCannotIndependentlyConsumeTheSameAllowance() throws Exception {
        UUID customer=UUID.randomUUID();String token=token();var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();return post("/internal/v1/reservations",body(UUID.randomUUID(),customer,80),"a",token).statusCode();});
            var b=pool.submit(()->{start.await();return post("/internal/v1/reservations",body(UUID.randomUUID(),customer,80),"b",token).statusCode();});start.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(201,409);}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM limit_reservations",Integer.class)).isEqualTo(1);
    }
    @Test void releaseIsIdempotentAndReturnsCapacityButCannotUndoConfirmation() throws Exception {
        UUID customer=UUID.randomUUID();String token=token();var first=post("/internal/v1/reservations",body(UUID.randomUUID(),customer,80),"a",token);String id=json.readTree(first.body()).get("id").asString();
        String path="/internal/v1/reservations/"+id+"/release";assertThat(post(path,"",null,token).statusCode()).isEqualTo(200);assertThat(post(path,"",null,token).statusCode()).isEqualTo(200);
        assertThat(post("/internal/v1/reservations",body(UUID.randomUUID(),customer,80),"b",token).statusCode()).isEqualTo(201);
        assertThat(post("/internal/v1/reservations/"+id+"/confirm","",null,token).statusCode()).isEqualTo(409);
    }
    @Test void enforcesPolicyKeysOwnershipAndSeparateScopes() throws Exception {
        UUID customer=UUID.randomUUID(),reference=UUID.randomUUID();String token=token();String body=body(reference,customer,80);var first=post("/internal/v1/reservations",body,"a",token);String id=json.readTree(first.body()).get("id").asString();
        assertThat(post("/internal/v1/reservations",body(reference,customer,81),"a",token).statusCode()).isEqualTo(409);
        assertThat(post("/internal/v1/reservations",body(UUID.randomUUID(),customer,101),"b",token).statusCode()).isEqualTo(409);
        assertThat(get("/internal/v1/reservations/"+id,service("partner-b","limits:reservations:read")).statusCode()).isEqualTo(404);
        assertThat(post("/internal/v1/reservations/"+id+"/release","",null,auth("partner-a","limits:reservations:settle","other-service","service")).statusCode()).isEqualTo(403);
        assertThat(post("/internal/v1/reservations",body,"x",null).statusCode()).isEqualTo(401);
        assertThat(post("/internal/v1/reservations",body(UUID.randomUUID(),customer,0),"invalid",token).statusCode()).isEqualTo(400);
    }

 @Test void cancellationFencesLateReservationCreation()throws Exception{
  UUID reference=UUID.randomUUID(),customer=UUID.randomUUID();String token=service("partner-a","limits:reservations:write limits:reservations:settle");
  var cancelled=post("/internal/v1/reservation-references/"+reference+"/cancel","",null,token);assertThat(cancelled.statusCode()).isEqualTo(200);
  var late=post("/internal/v1/reservations",json.writeValueAsString(Map.of("reference",reference,"customerId",customer,"amount",10,"currency","XOF")),"late",token);assertThat(late.statusCode()).isEqualTo(409);assertThat(late.body()).contains("RESERVATION_CANCELLED");
 }

}
