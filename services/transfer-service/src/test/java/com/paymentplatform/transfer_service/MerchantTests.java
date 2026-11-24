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
class MerchantTests {
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
        jdbc.execute("TRUNCATE merchant_events,merchants,transfers");
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
 @Test void merchantPaymentsReusePinAndFinancialSafetyWhileSuspensionStopsNewPayments()throws Exception{
  UUID merchant=UUID.randomUUID();String operator=auth("partner-a","merchants:write merchants:control","merchant-operator","operator");
  var created=post("/v1/merchants",json.writeValueAsString(Map.of("id",merchant,"walletId",RECIPIENT,"code","SHOP_001")),null,operator);assertThat(created.statusCode()).isEqualTo(201);
  String request=json.writeValueAsString(Map.of("merchantId",merchant,"senderWalletId",SENDER,"amount",100,"maxFee",20,"pin","739152")),paymentToken=service("partner-a","payments:write");
  var paid=post("/v1/merchant-payments",request,"merchant-one",paymentToken);assertThat(paid.statusCode()).isEqualTo(201);assertThat(post("/v1/merchant-payments",request,"merchant-one",paymentToken).body()).isEqualTo(paid.body());assertThat(POSTINGS.get()).isEqualTo(1);
  assertThat(post("/internal/v1/merchants/"+merchant+"/state",json.writeValueAsString(Map.of("id",UUID.randomUUID(),"state","SUSPENDED","expectedVersion",1,"reasonCode","INVESTIGATION")),null,operator).statusCode()).isEqualTo(200);
  assertThat(post("/v1/merchant-payments",request,"merchant-two",paymentToken).statusCode()).isEqualTo(409);
  assertThat(get("/v1/merchants/"+merchant,service("partner-b","merchants:read")).statusCode()).isEqualTo(404);
 }

}
