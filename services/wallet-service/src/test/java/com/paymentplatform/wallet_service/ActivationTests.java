package com.paymentplatform.wallet_service;

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
class ActivationTests {
    private static final Map<UUID,String> OWNERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger UPSTREAM_STATUS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> FORWARDED = new java.util.concurrent.atomic.AtomicReference<>();
    private static final String SCOPES = "wallets:write wallets:read customers:ownership:read";
    private static final java.util.concurrent.atomic.AtomicInteger MODE=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.Set<String> ACCOUNT_KEYS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final UUID LEDGER_ACCOUNT=new UUID(0,100);
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
        for(String target:List.of("customer","identity","ledger"))registry.add("boodoo.dependencies.origins."+target,()->"http://127.0.0.1:"+KEYS.getAddress().getPort());
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-wallet-api");
        registry.add("boodoo.security.customer-audience", () -> "boodoo-customer-api");
        registry.add("boodoo.customers.base-url", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort());
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE wallet_activations, wallet_creation_requests, wallets");
        OWNERS.clear(); UPSTREAM_STATUS.set(0); CALLS.set(0); FORWARDED.set(null);
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(List.of("boodoo-wallet-api", "boodoo-customer-api"))
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
    private UUID customer(int number,String tenant) {
        UUID id=new UUID(0,number); OWNERS.put(id,tenant); return id;
    }
    private String partner(String tenant) throws Exception { return auth(tenant, SCOPES, "client", null); }
    private HttpResponse<String> post(UUID customer,String currency,String key,String token) throws Exception {
        return rawPost("{\"customerId\":\""+customer+"\",\"currency\":\""+currency+"\"}",key,token);
    }
    private HttpResponse<String> rawPost(String body,String key,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/wallets"))
                .header("Content-Type","application/json").header("X-Integrator-Id","partner-b")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(key!=null)builder.header("Idempotency-Key",key);
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void counts(int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallets",Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_creation_requests",Integer.class)).isEqualTo(count);
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
            server.createContext("/internal/v1/customers/", exchange -> {
                CALLS.incrementAndGet(); int status=500;
                try {
                    String bearer=exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                    FORWARDED.set(bearer);
                    var claims=SignedJWT.parse(bearer).getJWTClaimsSet();
                    UUID id=UUID.fromString(exchange.getRequestURI().getPath().split("/")[4]);
                    if(!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().endsWith("/ownership"))status=400;
                    else if(!claims.getStringClaim("scope").contains("customers:ownership:read"))status=403;
                    else if(UPSTREAM_STATUS.get()!=0)status=UPSTREAM_STATUS.get();
                    else status=claims.getStringClaim("integrator_id").equals(OWNERS.get(id))?204:404;
                } catch(Exception ignored) { }
                exchange.sendResponseHeaders(status,-1);exchange.close();
            });
            server.createContext("/internal/v1/customers/00000000-0000-0000-0000-000000000001/kyc-approval",exchange->{
                String value=MODE.get()==5?"broken":"{\"customerId\":\"00000000-0000-0000-0000-000000000001\",\"kycStatus\":\"APPROVED\",\"approvalCurrent\":"+(MODE.get()!=1)+",\"checkedAt\":\""+Instant.now()+"\"}";reply(exchange,200,value);
            });
            server.createContext("/v1/customers/",exchange->reply(exchange,200,"{\"customerId\":\"00000000-0000-0000-0000-000000000001\",\"configured\":"+(MODE.get()!=2)+"}"));
            server.createContext("/internal/v1/accounts",exchange->{
                if(MODE.get()==3){reply(exchange,503,"{}");return;}
                if(exchange.getRequestURI().getPath().endsWith("/balance")){reply(exchange,200,"{\"accountId\":\""+LEDGER_ACCOUNT+"\",\"currency\":\"XOF\",\"normalBalance\":\"CREDIT\",\"amountMinorUnits\":"+(MODE.get()==4?1:0)+"}");return;}
                String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");ACCOUNT_KEYS.add(key);
                String code="W_"+key.substring("wallet-".length()).replace("-","").toUpperCase(Locale.ROOT);
                reply(exchange,201,"{\"id\":\""+LEDGER_ACCOUNT+"\",\"code\":\""+code+"\",\"type\":\"LIABILITY\",\"currency\":\"XOF\",\"normalBalance\":\"CREDIT\"}");
            });
            server.createContext("/internal/v1/accounts/"+LEDGER_ACCOUNT+"/control",exchange->{byte[] payload=("{\"accountId\":\""+LEDGER_ACCOUNT+"\",\"state\":\"OPEN\",\"version\":0}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,payload.length);try(var out=exchange.getResponseBody()){out.write(payload);}});
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static void reply(com.sun.net.httpserver.HttpExchange exchange,int status,String value)throws java.io.IOException{byte[] response=value.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,response.length);try(var out=exchange.getResponseBody()){out.write(response);}}
    @BeforeEach void resetActivationPeer(){MODE.set(0);ACCOUNT_KEYS.clear();}
    private String activationToken(String tenant)throws Exception{return auth(tenant,SCOPES+" wallets:activate wallets:eligibility:read customers:kyc:read pins:read ledger:accounts:write ledger:balances:read","wallet-service","service");}
    private UUID pending()throws Exception{return UUID.fromString(json.readTree(post(customer(1,"partner-a"),"XOF","create",partner("partner-a")).body()).get("id").asString());}
    private HttpResponse<String> activate(UUID id,String key,String token)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/wallets/"+id+"/activate")).POST(HttpRequest.BodyPublishers.noBody());if(key!=null)builder.header("Idempotency-Key",key);if(token!=null)builder.header("Authorization","Bearer "+token);return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void activatesOnceAndKeepsCreationReplayHistoricalWhileReadsAreLive()throws Exception{
        UUID id=pending();String token=activationToken("partner-a");var result=activate(id,"activate",token);assertThat(result.statusCode()).isEqualTo(201);
        assertThat(json.readTree(result.body()).get("status").asString()).isEqualTo("ACTIVE");assertThat(activate(id,"activate",token).body()).isEqualTo(result.body());assertThat(ACCOUNT_KEYS).hasSize(1);
        assertThat(json.readTree(get("/v1/wallets/"+id,token).body()).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(json.readTree(post(new UUID(0,1),"XOF","create",partner("partner-a")).body()).get("status").asString()).isEqualTo("PENDING");
        var state=json.readTree(get("/internal/v1/wallets/"+id+"/operational-state",token).body());assertThat(state.get("eligible").asBoolean()).isTrue();assertThat(state.get("ledgerAccountId").asString()).isEqualTo(LEDGER_ACCOUNT.toString());
        MODE.set(1);assertThat(json.readTree(get("/internal/v1/wallets/"+id+"/operational-state",token).body()).get("eligible").asBoolean()).isFalse();
        MODE.set(5);assertThat(get("/internal/v1/wallets/"+id+"/operational-state",token).statusCode()).isEqualTo(503);
    }
    @Test void failsClosedOnMissingApprovalPinOrLedgerAndResumesTheOriginalIntent()throws Exception{
        UUID id=pending();String token=activationToken("partner-a");
        for(int mode:List.of(1,2,3,4,5)){MODE.set(mode);assertThat(activate(id,"activate",token).statusCode()).isEqualTo(mode==3||mode==5?503:409);assertThat(jdbc.queryForObject("SELECT status FROM wallets",String.class)).isEqualTo("PENDING");}
        MODE.set(0);assertThat(activate(id,"activate",token).statusCode()).isEqualTo(201);assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_activations",Integer.class)).isEqualTo(1);
    }
    @Test void isolatesTenantsScopesAndBindsAnIntentToItsOriginalKeyAndActor()throws Exception{
        UUID id=pending();String token=activationToken("partner-a");assertThat(activate(id,"activate",token).statusCode()).isEqualTo(201);
        assertThat(activate(id,"other",token).statusCode()).isEqualTo(409);
        assertThat(activate(id,"activate",auth("partner-a",SCOPES+" wallets:activate","other-service","service")).statusCode()).isEqualTo(409);
        assertThat(activate(id,"foreign",activationToken("partner-b")).statusCode()).isEqualTo(404);
        assertThat(activate(id,"none",null).statusCode()).isEqualTo(401);assertThat(activate(id,"scope",partner("partner-a")).statusCode()).isEqualTo(403);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("DELETE FROM wallet_activations")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void concurrentActivationRetriesBindExactlyOneAccount()throws Exception{
        UUID id=pending();String token=activationToken("partner-a");var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();return activate(id,"same",token);});var b=pool.submit(()->{start.await();return activate(id,"same",token);});start.countDown();var one=a.get();var two=b.get();assertThat(one.statusCode()).isEqualTo(201);assertThat(two.statusCode()).isEqualTo(201);assertThat(one.body()).isEqualTo(two.body());}
        assertThat(ACCOUNT_KEYS).hasSize(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_activations WHERE snapshot IS NOT NULL",Integer.class)).isEqualTo(1);
    }

}
