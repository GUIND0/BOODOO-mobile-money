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
class WebhookTests {
    private static final String ISSUER = "https://directory-test.example";
    private static final RSAKey KEY = signingKey();
    private static final HttpServer KEYS = keys();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired com.paymentplatform.notification_service.domain.WebhookOutbox outbox;
    @Autowired com.paymentplatform.notification_service.domain.WebhookDispatcher dispatcher;
    @Autowired com.paymentplatform.notification_service.domain.NotificationService notifications;
    private static final java.util.concurrent.atomic.AtomicInteger STATUS=new java.util.concurrent.atomic.AtomicInteger(204);
    private static final java.util.concurrent.atomic.AtomicReference<String> SIGNATURE=new java.util.concurrent.atomic.AtomicReference<>(),PAYLOAD=new java.util.concurrent.atomic.AtomicReference<>(),TIMESTAMP=new java.util.concurrent.atomic.AtomicReference<>();
    private static final byte[] SECRET=new byte[32];
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("boodoo.webhooks.destinations-json",()->"{\"partner-a\":{\"endpoint\":\"http://127.0.0.1:"+KEYS.getAddress().getPort()+"/receiver\",\"version\":\"v1\",\"keyId\":\"test\",\"secretBase64\":\""+Base64.getEncoder().encodeToString(SECRET)+"\"}}");
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-notification-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE webhook_redrives,webhook_outbox,notifications");
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
            server.createContext("/receiver",x->{PAYLOAD.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));SIGNATURE.set(x.getRequestHeaders().getFirst("X-Boodoo-Signature"));TIMESTAMP.set(x.getRequestHeaders().getFirst("X-Boodoo-Timestamp"));x.sendResponseHeaders(STATUS.get(),-1);x.close();});
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

 @Test void commitsOutboxWithNotificationSignsExactBytesAndKeepsAcknowledgementSeparate(){
  UUID id=UUID.randomUUID();var request=new com.paymentplatform.notification_service.domain.NotificationService.Request(id,UUID.randomUUID(),UUID.randomUUID(),com.paymentplatform.notification_service.domain.NotificationService.Template.TRANSFER_POSTED);notifications.create("partner-a","transfer-service",request);notifications.create("partner-a","transfer-service",request);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_outbox",Integer.class)).isEqualTo(1);STATUS.set(204);assertThat(dispatcher.once()).isTrue();assertThat(outbox.get("partner-a",id).state()).isEqualTo("DELIVERED");assertThat(dispatcher.once()).isFalse();
  assertThat(SIGNATURE.get()).isEqualTo("v1="+com.paymentplatform.notification_service.domain.WebhookDispatcher.signature(SECRET,TIMESTAMP.get(),id,PAYLOAD.get()));assertThat(PAYLOAD.get()).contains("UNREAD");assertThat(jdbc.queryForObject("SELECT acknowledged_at FROM notifications WHERE id=?",java.time.OffsetDateTime.class,id)).isNull();
 }
 @Test void failedDeliveryRetriesThenDeadLettersAndRequiresExplicitRedrive(){
  UUID id=UUID.randomUUID();notifications.create("partner-a","transfer-service",new com.paymentplatform.notification_service.domain.NotificationService.Request(id,UUID.randomUUID(),UUID.randomUUID(),com.paymentplatform.notification_service.domain.NotificationService.Template.TRANSFER_POSTED));STATUS.set(503);dispatcher.once();assertThat(outbox.get("partner-a",id).state()).isEqualTo("READY");
  jdbc.update("UPDATE webhook_outbox SET attempts=8,next_attempt_at=clock_timestamp() WHERE id=?",id);dispatcher.once();assertThat(outbox.get("partner-a",id).state()).isEqualTo("DEAD");
  var request=new com.paymentplatform.notification_service.domain.WebhookOutbox.Retry(UUID.randomUUID(),"RECEIVER_FIXED");assertThat(outbox.retry("partner-a","operator",id,request).state()).isEqualTo("READY");STATUS.set(204);dispatcher.once();assertThat(outbox.get("partner-a",id).state()).isEqualTo("DELIVERED");assertThat(outbox.retry("partner-a","operator",id,request).state()).isEqualTo("READY");assertThat(outbox.get("partner-a",id).state()).isEqualTo("DELIVERED");
 }
 @Test void outboxFailureRollsBackNotificationAtomically(){
  jdbc.execute("CREATE FUNCTION fail_outbox_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected failure';END $$");jdbc.execute("CREATE TRIGGER fail_outbox_test BEFORE INSERT ON webhook_outbox FOR EACH ROW EXECUTE FUNCTION fail_outbox_test()");
  try{org.assertj.core.api.Assertions.assertThatThrownBy(()->notifications.create("partner-a","transfer-service",new com.paymentplatform.notification_service.domain.NotificationService.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),com.paymentplatform.notification_service.domain.NotificationService.Template.TRANSFER_POSTED))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications",Integer.class)).isZero();}
  finally{jdbc.execute("DROP TRIGGER fail_outbox_test ON webhook_outbox");jdbc.execute("DROP FUNCTION fail_outbox_test()");}
 }

}
