package com.paymentplatform.notification_service;

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
        registry.add("boodoo.security.audience", () -> "boodoo-notification-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE notifications");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-notification-api")
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
    private String token() throws Exception{return service("partner-a","notifications:write notifications:read notifications:ack");}
    private String body(UUID id,UUID customer){return json.writeValueAsString(Map.of("id",id,"customerId",customer,"reference",new UUID(0,99),"template","TRANSFER_POSTED"));}
    @Test void createsListsAndAcknowledgesWithoutPretendingExternalDelivery() throws Exception {
        UUID id=UUID.randomUUID(),customer=UUID.randomUUID();String body=body(id,customer);var first=post("/internal/v1/notifications",body,null,token());assertThat(first.statusCode()).isEqualTo(201);
        String path="/v1/customers/"+customer+"/notifications";var page=json.readTree(get(path,token()).body());assertThat(page.get("items").size()).isEqualTo(1);assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("UNREAD");
        var acknowledged=post(path+"/"+id+"/acknowledgement","",null,token());assertThat(acknowledged.statusCode()).isEqualTo(200);assertThat(json.readTree(acknowledged.body()).get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(post(path+"/"+id+"/acknowledgement","",null,token()).body()).isEqualTo(acknowledged.body());assertThat(post("/internal/v1/notifications",body,null,token()).body()).isEqualTo(first.body());
    }
    @Test void rejectsConflictsForeignAcknowledgementsAndUnsupportedOtpFields() throws Exception {
        UUID id=UUID.randomUUID(),customer=UUID.randomUUID();post("/internal/v1/notifications",body(id,customer),null,token());
        assertThat(post("/internal/v1/notifications",body(id,UUID.randomUUID()),null,token()).statusCode()).isEqualTo(409);
        String path="/v1/customers/"+customer+"/notifications";
        assertThat(json.readTree(get(path,service("partner-b","notifications:read")).body()).get("items").size()).isZero();
        assertThat(post(path+"/"+id+"/acknowledgement","",null,service("partner-b","notifications:ack")).statusCode()).isEqualTo(404);
        assertThat(post("/internal/v1/notifications","{\"otp\":\"123456\"}",null,token()).statusCode()).isEqualTo(400);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("DELETE FROM notifications")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void enforcesScopedServiceIdentityAndBoundedPages() throws Exception {
        UUID customer=UUID.randomUUID();String path="/v1/customers/"+customer+"/notifications";
        assertThat(get(path,null).statusCode()).isEqualTo(401);assertThat(get(path,service("partner-a","notifications:write")).statusCode()).isEqualTo(403);
        assertThat(get(path,auth("partner-a","notifications:read","operator","operator")).statusCode()).isEqualTo(403);
        assertThat(get(path+"?limit=101",token()).statusCode()).isEqualTo(400);assertThat(get(path+"?after=invalid",token()).statusCode()).isEqualTo(400);
    }

}
