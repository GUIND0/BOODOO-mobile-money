package com.paymentplatform.distribution_service;

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
    private static final UUID ACCOUNT=new UUID(0,100),WALLET_ACCOUNT=new UUID(0,101),CUSTOMER=new UUID(0,102),WALLET=new UUID(0,103);
    private static final ObjectMapper PEER_JSON=new ObjectMapper();
    private static final Map<String,String> RECEIPTS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger POSTINGS=new java.util.concurrent.atomic.AtomicInteger(),MODE=new java.util.concurrent.atomic.AtomicInteger();

    private static final String ISSUER = "https://directory-test.example";
    private static final RSAKey KEY = signingKey();
    private static final HttpServer KEYS = keys();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Autowired com.paymentplatform.distribution_service.domain.CashStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        for(String target:List.of("wallet","identity","ledger","risk","limits"))registry.add("boodoo.dependencies.origins."+target,()->"http://127.0.0.1:"+KEYS.getAddress().getPort());
        registry.add("boodoo.cash.zero-fee-enabled",()->true);
        registry.add("boodoo.cash.tokens-json",()->"{\"partner-a\":\""+serviceBearer()+"\"}");
        registry.add("boodoo.cash.accounts-json",()->"{\"partner-a\":\""+ACCOUNT+"\"}");
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-distribution-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE cash_operations");RECEIPTS.clear();POSTINGS.set(0);MODE.set(0);
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-distribution-api")
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
            server.createContext("/",exchange->{try{peer(exchange);}catch(Exception failure){reply(exchange,500,Map.of());}});
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

 @Test void cashIntentRequiresIndependentApprovalAndProtectsEvidence(){
  var request=new com.paymentplatform.distribution_service.domain.CashWorkflow.Request(UUID.randomUUID(),"IN",100L,"SETTLEMENT_001",null);
  var row=store.begin("partner-a","cashier","one",request);assertThat(store.begin("partner-a","cashier","one",request).id()).isEqualTo(row.id());
  UUID lease=store.claim("partner-a",row.id());store.save("partner-a",row.id(),lease,"REQUESTED",new com.paymentplatform.distribution_service.domain.CashStore.Context(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,Instant.now().plusSeconds(300)),null);store.release("partner-a",row.id(),lease);
  org.assertj.core.api.Assertions.assertThatThrownBy(()->store.approve("partner-a","cashier",row.id())).isInstanceOf(com.paymentplatform.distribution_service.api.ApiException.class);
  assertThat(store.approve("partner-a","checker",row.id()).state()).isEqualTo("APPROVED");assertThat(store.approve("partner-a","checker",row.id()).checker()).isEqualTo("checker");
  org.assertj.core.api.Assertions.assertThatThrownBy(()->store.begin("partner-a","cashier","two",request)).isInstanceOf(com.paymentplatform.distribution_service.api.ApiException.class);
  org.assertj.core.api.Assertions.assertThatThrownBy(()->store.get("partner-b",row.id())).isInstanceOf(com.paymentplatform.distribution_service.api.ApiException.class);
 }
 @Test void leasesFenceConcurrentUpdatesAndDisbursementRequiresAccounting(){
  var row=store.begin("partner-a","cashier","one",new com.paymentplatform.distribution_service.domain.CashWorkflow.Request(UUID.randomUUID(),"OUT",100L,"CASH_001","739152"));UUID lease=store.claim("partner-a",row.id());assertThat(lease).isNotNull();assertThat(store.claim("partner-a",row.id())).isNull();
  org.assertj.core.api.Assertions.assertThatThrownBy(()->store.save("partner-a",row.id(),UUID.randomUUID(),"REQUESTED",null,null)).isInstanceOf(com.paymentplatform.distribution_service.api.ApiException.class);
  org.assertj.core.api.Assertions.assertThatThrownBy(()->store.disburse("partner-a","cashier",row.id())).isInstanceOf(com.paymentplatform.distribution_service.api.ApiException.class);
  assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM cash_operations c",String.class)).doesNotContain("739152");
 }
 @Test void anonymousOrServiceCallsCannotAssertCashEvidence()throws Exception{
  var response=post("/internal/v1/cash-operations",json.writeValueAsString(Map.of("walletId",WALLET,"direction","IN","amount",100,"evidence","ONE")),"one",service("partner-a","cash:request"));assertThat(response.statusCode()).isEqualTo(403);
 }

 private static String serviceBearer(){try{var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-distribution-api").subject("distribution-service").claim("actor_type","service").claim("integrator_id","partner-a").expirationTime(Date.from(Instant.now().plusSeconds(600))).build());jwt.sign(new RSASSASigner(KEY));return jwt.serialize();}catch(Exception e){throw new IllegalStateException(e);}}
 private static void reply(com.sun.net.httpserver.HttpExchange x,int status,Object body)throws java.io.IOException{if(status==204){x.sendResponseHeaders(204,-1);x.close();return;}byte[] bytes=(body instanceof String text?text:PEER_JSON.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}}
 private static void peer(com.sun.net.httpserver.HttpExchange x)throws Exception{
  String path=x.getRequestURI().getPath(),raw=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);var body=raw.isBlank()?PEER_JSON.createObjectNode():PEER_JSON.readTree(raw);
  if(path.startsWith("/internal/v1/wallets/")){reply(x,200,Map.of("walletId",WALLET,"customerId",CUSTOMER,"ledgerAccountId",WALLET_ACCOUNT,"currency","XOF","status","ACTIVE","eligible",true,"checkedAt",Instant.now().toString()));return;}
  if(path.startsWith("/internal/v1/accounts/")){reply(x,200,Map.of("id",ACCOUNT,"type","ASSET","currency","XOF"));return;}
  if(path.endsWith("/pin/verify")){reply(x,body.get("pin").asString().equals("739152")?204:422,Map.of());return;}
  if(path.equals("/internal/v1/evaluations")){var result=PEER_JSON.createObjectNode();result.put("id",body.get("id").asString());result.put("reference",body.get("reference").asString());result.put("customerId",CUSTOMER.toString());result.put("amount",100);result.put("currency","XOF");result.put("outcome","ALLOW");result.put("expiresAt",Instant.now().plusSeconds(60).toString());reply(x,201,result);return;}
  if(path.equals("/internal/v1/reservations")){reply(x,201,Map.of("id",new UUID(0,110),"reference",body.get("reference").asString(),"customerId",CUSTOMER,"amount",100,"currency","XOF","status","RESERVED","expiresAt",Instant.now().plusSeconds(300).toString()));return;}
  if(path.endsWith("/confirm")){reply(x,200,Map.of("id",new UUID(0,110),"status","CONFIRMED"));return;}
  if(path.equals("/internal/v1/journals/by-reference")){String ref=x.getRequestURI().getQuery().substring(10);String receipt=RECEIPTS.get(ref);reply(x,receipt==null?404:200,receipt==null?Map.of():receipt);return;}
  if(path.equals("/internal/v1/journals")){String ref=body.get("reference").asString(),receipt=PEER_JSON.writeValueAsString(Map.of("id",new UUID(0,120),"reference",ref,"currency","XOF","entries",body.get("entries")));if(RECEIPTS.putIfAbsent(ref,receipt)==null)POSTINGS.incrementAndGet();reply(x,MODE.get()==1?503:201,MODE.get()==1?Map.of():receipt);return;}
  reply(x,404,Map.of());
 }
 @Test void cashInResolvesLostPostingAcknowledgementWithoutCreatingMoneyTwice()throws Exception{
  String maker=auth("partner-a","cash:request","cashier","operator"),checker=auth("partner-a","cash:approve","checker","operator");
  var intent=post("/internal/v1/cash-operations",json.writeValueAsString(Map.of("walletId",WALLET,"direction","IN","amount",100,"evidence","RECEIPT_001")),"in",maker);assertThat(intent.statusCode()).isEqualTo(201);String id=json.readTree(intent.body()).get("id").asString(),path="/internal/v1/cash-operations/"+id+"/approval";
  MODE.set(1);assertThat(post(path,"",null,checker).statusCode()).isEqualTo(202);assertThat(POSTINGS.get()).isEqualTo(1);
  MODE.set(0);var recovered=post(path,"",null,checker);assertThat(recovered.statusCode()).isEqualTo(200);assertThat(json.readTree(recovered.body()).get("state").asString()).isEqualTo("ACCOUNTED");assertThat(POSTINGS.get()).isEqualTo(1);
 }
 @Test void cashOutRequiresPinBeforeApprovalAndSeparatePhysicalDeliveryAcknowledgement()throws Exception{
  String maker=auth("partner-a","cash:request cash:disburse","cashier","operator"),checker=auth("partner-a","cash:approve","checker","operator");
  String body=json.writeValueAsString(Map.of("walletId",WALLET,"direction","OUT","amount",100,"evidence","PAYOUT_001","pin","123456"));assertThat(post("/internal/v1/cash-operations",body,"out",maker).statusCode()).isEqualTo(422);
  var intent=post("/internal/v1/cash-operations",body.replace("123456","739152"),"out",maker);assertThat(intent.statusCode()).isEqualTo(201);String id=json.readTree(intent.body()).get("id").asString();
  var accounted=post("/internal/v1/cash-operations/"+id+"/approval","",null,checker);assertThat(accounted.statusCode()).isEqualTo(200);assertThat(json.readTree(accounted.body()).get("state").asString()).isEqualTo("ACCOUNTED");
  var delivered=post("/internal/v1/cash-operations/"+id+"/disbursement","",null,maker);assertThat(delivered.statusCode()).isEqualTo(200);assertThat(json.readTree(delivered.body()).get("state").asString()).isEqualTo("DISBURSED");assertThat(POSTINGS.get()).isEqualTo(1);
 }

}
