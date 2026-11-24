package com.paymentplatform.transfer_service;

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
    private static final UUID SENDER=new UUID(0,10),RECIPIENT=new UUID(0,20),SENDER_CUSTOMER=new UUID(0,1),RECIPIENT_CUSTOMER=new UUID(0,2),SENDER_ACCOUNT=new UUID(0,101),RECIPIENT_ACCOUNT=new UUID(0,102),FEE_ACCOUNT=new UUID(0,103);
    private static final ObjectMapper PEER_JSON=new ObjectMapper();
    private static final java.util.concurrent.atomic.AtomicInteger MODE=new java.util.concurrent.atomic.AtomicInteger(),POSTINGS=new java.util.concurrent.atomic.AtomicInteger(),PIN_COMPARISONS=new java.util.concurrent.atomic.AtomicInteger();
    private static final Map<String,String> RECEIPTS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> EVENTS=java.util.concurrent.ConcurrentHashMap.newKeySet(),NOTIFICATIONS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_BEARER=new java.util.concurrent.atomic.AtomicReference<>();
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
        for(String target:List.of("wallet","pricing","identity","limits","risk","ledger","audit","notification"))registry.add("boodoo.dependencies.origins."+target,()->"http://127.0.0.1:"+KEYS.getAddress().getPort());
        registry.add("boodoo.transfers.service-tokens-json",()->"{\"partner-a\":\""+serviceBearer()+"\"}");
        registry.add("boodoo.transfers.fee-accounts-json",()->"{\"partner-a\":\""+FEE_ACCOUNT+"\"}");
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-transfer-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE transfers");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-transfer-api")
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
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory()));
            server.createContext("/",exchange->{try{peer(exchange);}catch(Exception error){reply(exchange,500,Map.of("code","TEST_PEER_ERROR"));}});
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static String serviceBearer(){try{
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).audience(List.of("boodoo-transfer-api","boodoo-wallet-api","boodoo-customer-api","boodoo-identity-api","boodoo-pricing-api","boodoo-limits-api","boodoo-risk-compliance-api","boodoo-ledger-api","boodoo-audit-api","boodoo-notification-api")).subject("transfer-service").claim("actor_type","service").claim("integrator_id","partner-a").claim("scope","transfers:resume wallets:eligibility:read customers:kyc:read customers:ownership:read pins:read pins:verify pricing:quotes:write limits:reservations:write limits:reservations:settle risk:evaluations:write ledger:accounts:read ledger:journals:read ledger:journals:write audit:events:write notifications:write").expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims);jwt.sign(new RSASSASigner(KEY));return jwt.serialize();
    }catch(Exception error){throw new IllegalStateException(error);}}
    private static UUID derived(String kind,String reference){return UUID.nameUUIDFromBytes((kind+reference).getBytes(StandardCharsets.UTF_8));}
    private static void reply(com.sun.net.httpserver.HttpExchange exchange,int status,Object value)throws java.io.IOException {
        if(status==204){exchange.sendResponseHeaders(204,-1);exchange.close();return;}
        byte[] bytes=(value instanceof String text?text:PEER_JSON.writeValueAsString(value)).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    }
    private static void peer(com.sun.net.httpserver.HttpExchange exchange)throws Exception {
        LAST_BEARER.set(exchange.getRequestHeaders().getFirst("Authorization"));String path=exchange.getRequestURI().getPath();String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);var body=raw.isBlank()?PEER_JSON.createObjectNode():PEER_JSON.readTree(raw);
        if(path.startsWith("/internal/v1/wallets/")){
            UUID id=UUID.fromString(path.split("/")[4]);if(!id.equals(SENDER)&&!id.equals(RECIPIENT)){reply(exchange,404,Map.of());return;}
            reply(exchange,200,Map.of("walletId",id,"customerId",id.equals(SENDER)?SENDER_CUSTOMER:RECIPIENT_CUSTOMER,"ledgerAccountId",id.equals(SENDER)?SENDER_ACCOUNT:RECIPIENT_ACCOUNT,"currency","XOF","status","ACTIVE","eligible",MODE.get()!=6,"checkedAt",Instant.now().toString()));return;
        }
        if(path.equals("/internal/v1/quotes")){
            String reference=body.get("reference").asString();long amount=body.get("amount").asLong();
            reply(exchange,201,Map.of("id",derived("quote",reference),"reference",reference,"amount",MODE.get()==5?amount+1:amount,"fee",10,"totalDebit",amount+10,"currency","XOF","expiresAt",Instant.now().plusSeconds(300).toString()));return;
        }
        if(path.endsWith("/pin/verify")){PIN_COMPARISONS.incrementAndGet();reply(exchange,body.get("pin").asString().equals("739152")?204:422,Map.of("code","PIN_INVALID"));return;}
        if(path.equals("/internal/v1/evaluations")){reply(exchange,201,Map.of("id",body.get("id").asString(),"reference",body.get("reference").asString(),"customerId",body.get("customerId").asString(),"amount",body.get("amount").asLong(),"currency","XOF","outcome",MODE.get()==2?"DENY":"ALLOW","expiresAt",Instant.now().plusSeconds(60).toString()));return;}
        if(path.equals("/internal/v1/reservations")){String reference=body.get("reference").asString();reply(exchange,201,Map.of("id",derived("reservation",reference),"reference",reference,"customerId",body.get("customerId").asString(),"amount",body.get("amount").asLong(),"currency","XOF","status","RESERVED","expiresAt",Instant.now().plusSeconds(300).toString()));return;}
        if(path.endsWith("/confirm")){reply(exchange,200,Map.of("id",path.split("/")[4],"status","CONFIRMED"));return;}
        if(path.startsWith("/internal/v1/accounts/")){reply(exchange,200,Map.of("id",FEE_ACCOUNT,"type","INCOME","normalBalance","CREDIT","currency","XOF"));return;}
        if(path.equals("/internal/v1/journals/by-reference")){String reference=exchange.getRequestURI().getQuery().substring("reference=".length());String receipt=RECEIPTS.get(reference);reply(exchange,receipt==null?404:200,receipt==null?Map.of():receipt);return;}
        if(path.equals("/internal/v1/journals")){
            String reference=body.get("reference").asString();String receipt=PEER_JSON.writeValueAsString(Map.of("id",derived("journal",reference),"reference",reference,"currency","XOF","createdAt",Instant.now().toString(),"entries",body.get("entries")));
            String prior=RECEIPTS.putIfAbsent(reference,receipt);if(prior==null)POSTINGS.incrementAndGet();if(MODE.get()==3){reply(exchange,503,Map.of("code","LOST_ACK_AFTER_COMMIT"));return;}reply(exchange,201,prior==null?receipt:prior);return;
        }
        if(path.equals("/internal/v1/events")){EVENTS.add(body.get("id").asString());reply(exchange,201,Map.of("id",body.get("id").asString()));return;}
        if(path.equals("/internal/v1/notifications")){if(MODE.get()==4){reply(exchange,503,Map.of());return;}NOTIFICATIONS.add(body.get("id").asString());reply(exchange,201,Map.of("id",body.get("id").asString()));return;}
        reply(exchange,404,Map.of());
    }
    @BeforeEach void resetPeers(){MODE.set(0);POSTINGS.set(0);PIN_COMPARISONS.set(0);RECEIPTS.clear();EVENTS.clear();NOTIFICATIONS.clear();LAST_BEARER.set(null);}
    private String token()throws Exception{return service("partner-a","transfers:write transfers:read");}
    private String body(long maxFee,String pin){return PEER_JSON.writeValueAsString(Map.of("senderWalletId",SENDER,"recipientWalletId",RECIPIENT,"amount",100,"maxFee",maxFee,"currency","XOF","pin",pin));}
    private String id(HttpResponse<String> response){return json.readTree(response.body()).get("id").asString();}
    private HttpResponse<String> transfer(String key)throws Exception{return post("/v1/transfers",body(20,"739152"),key,token());}
    private HttpResponse<String> resume(String id)throws Exception{return post("/internal/v1/transfers/"+id+"/resume","",null,auth("partner-a","transfers:resume","transfer-service","service"));}
    @Test void completesOneBalancedP2PWithFeeAndDedicatedServiceCredentials()throws Exception {
        String publicToken=token();var response=post("/v1/transfers",body(20,"739152"),"one",publicToken);assertThat(response.statusCode()).isEqualTo(201);var view=json.readTree(response.body());assertThat(view.get("status").asString()).isEqualTo("COMPLETED");assertThat(view.get("fee").asLong()).isEqualTo(10);
        assertThat(POSTINGS.get()).isEqualTo(1);assertThat(PIN_COMPARISONS.get()).isEqualTo(1);assertThat(EVENTS).hasSize(1);assertThat(NOTIFICATIONS).hasSize(2);
        var receipt=PEER_JSON.readTree(RECEIPTS.get(id(response)));long debit=0,credit=0;for(var entry:receipt.get("entries")){if(entry.get("side").asString().equals("DEBIT"))debit+=entry.get("amount").asLong();else credit+=entry.get("amount").asLong();}assertThat(debit).isEqualTo(110);assertThat(credit).isEqualTo(110);
        assertThat(LAST_BEARER.get()).isNotEqualTo("Bearer "+publicToken);assertThat(SignedJWT.parse(LAST_BEARER.get().substring(7)).getJWTClaimsSet().getSubject()).isEqualTo("transfer-service");
        String persisted=jdbc.queryForObject("SELECT to_jsonb(t)::text FROM transfers t",String.class);assertThat(persisted).doesNotContain("\"pin\"","739152",publicToken,LAST_BEARER.get());
    }
    @Test void retriesKeepTheFinancialIntentAndNeverReauthorizeOrDebitAgain()throws Exception {
        var original=transfer("same");var replay=post("/v1/transfers",body(20,"123456"),"same",token());assertThat(replay.statusCode()).isEqualTo(201);assertThat(replay.body()).isEqualTo(original.body());assertThat(PIN_COMPARISONS.get()).isEqualTo(1);assertThat(POSTINGS.get()).isEqualTo(1);
        assertThat(post("/v1/transfers",body(21,"739152"),"same",token()).statusCode()).isEqualTo(409);
        assertThat(get("/v1/transfers/"+id(original),token()).body()).isEqualTo(original.body());assertThat(json.readTree(get("/v1/transfers?walletId="+SENDER,token()).body()).get("items").size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("UPDATE transfers SET amount=101")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void lostLedgerAcknowledgementRemainsPostingThenResolvesByReferenceWithoutAnotherDebit()throws Exception {
        MODE.set(3);var uncertain=transfer("uncertain");assertThat(uncertain.statusCode()).isEqualTo(202);assertThat(json.readTree(uncertain.body()).get("status").asString()).isEqualTo("POSTING");assertThat(POSTINGS.get()).isEqualTo(1);
        String worker=auth("partner-a","transfers:resume","transfer-service","service");assertThat(json.readTree(get("/internal/v1/transfers/recoverable",worker).body()).size()).isEqualTo(1);
        MODE.set(0);var recovered=resume(id(uncertain));assertThat(recovered.statusCode()).isEqualTo(201);assertThat(POSTINGS.get()).isEqualTo(1);assertThat(PIN_COMPARISONS.get()).isEqualTo(1);
    }
    @Test void failedNotificationsNeverMakeAPostedTransferLookRejected()throws Exception {
        MODE.set(4);var posted=transfer("notification-failure");assertThat(posted.statusCode()).isEqualTo(202);assertThat(json.readTree(posted.body()).get("status").asString()).isEqualTo("POSTED");assertThat(json.readTree(posted.body()).get("journalId").isNull()).isFalse();
        MODE.set(0);assertThat(resume(id(posted)).statusCode()).isEqualTo(201);assertThat(POSTINGS.get()).isEqualTo(1);assertThat(EVENTS).hasSize(1);assertThat(NOTIFICATIONS).hasSize(2);
    }
    @Test void feeCapInvalidPinAndRiskDenialRejectBeforeFinancialPosting()throws Exception {
        assertThat(post("/v1/transfers",body(1,"739152"),"fee",token()).statusCode()).isEqualTo(422);assertThat(PIN_COMPARISONS.get()).isZero();
        assertThat(post("/v1/transfers",body(20,"123456"),"pin",token()).statusCode()).isEqualTo(422);MODE.set(2);assertThat(transfer("risk").statusCode()).isEqualTo(422);
        assertThat(POSTINGS.get()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE state='REJECTED'",Integer.class)).isEqualTo(3);
    }
    @Test void malformedFactsNeverAuthorizeAndAWorkerCannotSupplyMissingPinAuthorization()throws Exception {
        MODE.set(5);var pending=transfer("malformed");assertThat(pending.statusCode()).isEqualTo(202);assertThat(json.readTree(pending.body()).get("status").asString()).isEqualTo("CREATED");
        MODE.set(0);assertThat(resume(id(pending)).statusCode()).isEqualTo(202);assertThat(PIN_COMPARISONS.get()).isZero();assertThat(POSTINGS.get()).isZero();
        assertThat(post("/internal/v1/transfers/"+id(pending)+"/resume","",null,service("partner-a","transfers:resume")).statusCode()).isEqualTo(403);
    }
    @Test void concurrentRetriesShareOneLeaseAndOnePosting()throws Exception {
        String request=body(20,"739152"),token=token();var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();for(int n=0;n<6;n++)futures.add(pool.submit(()->{start.await();return post("/v1/transfers",request,"same",token);}));start.countDown();for(var future:futures)assertThat(future.get().statusCode()).isIn(201,202);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers",Integer.class)).isEqualTo(1);assertThat(POSTINGS.get()).isEqualTo(1);assertThat(PIN_COMPARISONS.get()).isEqualTo(1);
    }
    @Test void enforcesCallerScopesTenantIsolationAndStrictIntegerMoney()throws Exception {
        var response=transfer("read");assertThat(get("/v1/transfers/"+id(response),service("partner-b","transfers:read")).statusCode()).isEqualTo(404);
        assertThat(post("/v1/transfers",body(20,"739152"),"none",null).statusCode()).isEqualTo(401);assertThat(post("/v1/transfers",body(20,"739152"),"scope",service("partner-a","transfers:read")).statusCode()).isEqualTo(403);
        String fractional=body(20,"739152").replace("\"amount\":100","\"amount\":100.5");assertThat(post("/v1/transfers",fractional,"fraction",token()).statusCode()).isEqualTo(400);
        String numericPin=body(20,"739152").replace("\"pin\":\"739152\"","\"pin\":739152");assertThat(post("/v1/transfers",numericPin,"numeric-pin",token()).statusCode()).isEqualTo(400);
    }

}
