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
class WalletServiceApplicationTests {
    private static final Map<UUID,String> OWNERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger UPSTREAM_STATUS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> FORWARDED = new java.util.concurrent.atomic.AtomicReference<>();
    private static final String SCOPES = "wallets:write wallets:read customers:ownership:read";
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
        registry.add("boodoo.security.audience", () -> "boodoo-wallet-api");
        registry.add("boodoo.security.customer-audience", () -> "boodoo-customer-api");
        registry.add("boodoo.customers.base-url", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort());
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE wallet_creation_requests, wallets");
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
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @Test void provisionsAndReadsOnlyPendingMetadataForAnOwnedCustomer() throws Exception {
        UUID customer=customer(1,"partner-a");String token=partner("partner-a");
        var response=post(customer," xof ","create",token);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        var body=json.readTree(response.body());assertThat(body.size()).isEqualTo(5);
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("currency").asString()).isEqualTo("XOF");
        assertThat(body.get("customerId").asString()).isEqualTo(customer.toString());
        assertThat(body.has("balance")).isFalse();
        var read=get(response.headers().firstValue("Location").orElseThrow(),token);
        assertThat(read.statusCode()).isEqualTo(200);assertThat(read.body()).isEqualTo(response.body());
        assertThat(FORWARDED.get()).isEqualTo(token);counts(1);
    }
    @Test void replaysCanonicalRequestsAndRejectsKeyReuseForAnotherOwnedCustomer() throws Exception {
        UUID a=customer(1,"partner-a"), b=customer(2,"partner-a");String token=partner("partner-a");
        var original=post(a,"XOF","same",token);
        var replay=post(a,"xof","same",token);
        assertThat(replay.statusCode()).isEqualTo(201);assertThat(replay.body()).isEqualTo(original.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        var conflict=post(b,"XOF","same",token);assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        counts(1);assertThat(CALLS.get()).isEqualTo(3);
    }
    @Test void preventsDuplicateWalletsAcrossDifferentKeys() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        assertThat(post(id,"XOF","one",token).statusCode()).isEqualTo(201);
        var duplicate=post(id,"XOF","two",token);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).get("code").asString()).isEqualTo("WALLET_ALREADY_EXISTS");counts(1);
    }
    @Test void isolatesPartnersAndKeyNamespacesAndHidesForeignWallets() throws Exception {
        UUID a=customer(1,"partner-a"),b=customer(2,"partner-b");
        var first=post(a,"XOF","same",partner("partner-a"));
        var second=post(b,"XOF","same",partner("partner-b"));
        assertThat(first.statusCode()).isEqualTo(201);assertThat(second.statusCode()).isEqualTo(201);
        assertThat(post(b,"XOF","foreign",partner("partner-a")).statusCode()).isEqualTo(404);
        assertThat(post(UUID.randomUUID(),"XOF","unknown",partner("partner-a")).statusCode()).isEqualTo(404);
        String path=first.headers().firstValue("Location").orElseThrow();
        assertThat(get(path,partner("partner-b")).statusCode()).isEqualTo(404);
        assertThat(get("/v1/wallets/"+UUID.randomUUID(),partner("partner-a")).statusCode()).isEqualTo(404);counts(2);
    }
    @Test void checksOwnershipAgainOnReplayAndReads() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        var first=post(id,"XOF","same",token);OWNERS.remove(id);
        assertThat(post(id,"XOF","same",token).statusCode()).isEqualTo(404);
        assertThat(get(first.headers().firstValue("Location").orElseThrow(),token).statusCode()).isEqualTo(404);counts(1);
    }
    @Test void failsClosedOnOwnershipErrorsAndMissingOwnershipScope() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        for(int status:List.of(500,401,302)) {
            UPSTREAM_STATUS.set(status);var response=post(id,"XOF","key",token);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("OWNERSHIP_UNAVAILABLE");
        }
        UPSTREAM_STATUS.set(0);
        assertThat(post(id,"XOF","key",auth("partner-a","wallets:write","client",null)).statusCode()).isEqualTo(403);counts(0);
    }
    @Test void requiresScopesAndBothIntendedAudiencesBeforeCallingCustomerService() throws Exception {
        UUID id=customer(1,"partner-a");
        assertThat(post(id,"XOF","key",null).statusCode()).isEqualTo(401);
        assertThat(post(id,"XOF","key",auth("partner-a","wallets:read customers:ownership:read","client",null)).statusCode()).isEqualTo(403);
        String valid=partner("partner-a");
        var claims=new JWTClaimsSet.Builder(SignedJWT.parse(valid).getJWTClaimsSet()).audience("boodoo-wallet-api").build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims);jwt.sign(new RSASSASigner(KEY));
        assertThat(post(id,"XOF","key",jwt.serialize()).statusCode()).isEqualTo(401);
        assertThat(CALLS.get()).isZero();counts(0);
    }
    @Test void validatesRequestKeyCurrencyAndRejectsUnexpectedFields() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        for(String body:List.of("{}","{broken","{\"customerId\":\"invalid\",\"currency\":\"XOF\"}",
                "{\"customerId\":\""+id+"\",\"currency\":\"XOF\",\"status\":\"ACTIVE\"}"))
            assertThat(rawPost(body,"key",token).statusCode()).isEqualTo(400);
        assertThat(post(id,"EUR","key",token).statusCode()).isEqualTo(400);
        assertThat(post(id,"XOF",null,token).statusCode()).isEqualTo(400);
        assertThat(post(id,"XOF","invalid key",token).statusCode()).isEqualTo(400);
        assertThat(get("/v1/wallets/invalid",token).statusCode()).isEqualTo(400);counts(0);
    }
    @Test void concurrentSameKeyRequestsCreateExactlyOneWalletAndOneSnapshot() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for(int n=0;n<6;n++) futures.add(pool.submit(()->{start.await();return post(id,"XOF","same",token);}));
            start.countDown();Set<String> bodies=new HashSet<>();int original=0;
            for(var future:futures){var response=future.get(20,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);bodies.add(response.body());
                if(response.headers().firstValue("Idempotency-Replayed").orElseThrow().equals("false"))original++;
            }
            assertThat(original).isEqualTo(1);assertThat(bodies).hasSize(1);
        }
        counts(1);
    }
    @Test void concurrentDistinctKeysCannotBypassCustomerCurrencyUniqueness() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{start.await();return post(id,"XOF","one",token).statusCode();});
            var b=pool.submit(()->{start.await();return post(id,"XOF","two",token).statusCode();});start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(201,409);
        }
        counts(1);
    }
    @Test void storageFailureRollsBackWalletAndSnapshotAndAllowsRetry() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        jdbc.execute("CREATE FUNCTION reject_wallet_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_wallet_test BEFORE INSERT ON wallet_creation_requests FOR EACH ROW EXECUTE FUNCTION reject_wallet_test()");
        try {
            var response=post(id,"XOF","retry",token);assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("Injected failure","INSERT","SQLException");counts(0);
        } finally {
            jdbc.execute("DROP TRIGGER reject_wallet_test ON wallet_creation_requests");jdbc.execute("DROP FUNCTION reject_wallet_test()");
        }
        assertThat(post(id,"XOF","retry",token).statusCode()).isEqualTo(201);counts(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("DELETE FROM wallet_creation_requests"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void readRequiresReadScopeAndNeverReturnsMetadataWhenOwnershipIsUnavailable() throws Exception {
        UUID id=customer(1,"partner-a");String token=partner("partner-a");
        var response=post(id,"XOF","key",token);String path=response.headers().firstValue("Location").orElseThrow();
        assertThat(get(path,auth("partner-a","wallets:write customers:ownership:read","client",null)).statusCode()).isEqualTo(403);
        UPSTREAM_STATUS.set(500);var read=get(path,token);
        assertThat(read.statusCode()).isEqualTo(503);assertThat(read.body()).doesNotContain("createdAt","PENDING");
    }

}
