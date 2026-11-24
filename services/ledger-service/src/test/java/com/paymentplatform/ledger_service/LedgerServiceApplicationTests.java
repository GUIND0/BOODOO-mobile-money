package com.paymentplatform.ledger_service;

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
class LedgerServiceApplicationTests {
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
        registry.add("boodoo.security.audience", () -> "boodoo-ledger-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE journal_reversals,journal_requests,journal_entries,journals,account_creation_requests,ledger_accounts");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-ledger-api")
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
    private static final String SCOPES="ledger:accounts:write ledger:accounts:read";
    private String service(String tenant) throws Exception { return auth(tenant,SCOPES,"wallet-service","service"); }
    private HttpResponse<String> post(String code,String type,String key,String token) throws Exception {
        return raw("{\"code\":\""+code+"\",\"currency\":\"xof\",\"type\":\""+type+"\"}",key,token);
    }
    private HttpResponse<String> raw(String body,String key,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/accounts"))
                .header("Content-Type","application/json").header("X-Integrator-Id","partner-b")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(key!=null)request.header("Idempotency-Key",key);
        if(token!=null)request.header("Authorization","Bearer "+token);
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void counts(int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_accounts",Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_creation_requests",Integer.class)).isEqualTo(count);
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
    @Test void startsWithMigrationAndExposesHealthWithoutAuthentication() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class)).isGreaterThanOrEqualTo(3);
        var response=get("/actuator/health",null);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        assertThat(response.body()).doesNotContain("components");
    }
    @Test void createsEachAccountClassificationWithDerivedNormalSideAndAuditedActor() throws Exception {
        String token=service("partner-a");
        for(String type:List.of("ASSET","LIABILITY","EQUITY","INCOME","EXPENSE")) {
            var response=post(" account_"+type.toLowerCase()+" ",type,type,token);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
            var body=json.readTree(response.body());assertThat(body.size()).isEqualTo(6);
            assertThat(body.get("code").asString()).isEqualTo("ACCOUNT_"+type);
            assertThat(body.get("currency").asString()).isEqualTo("XOF");
            assertThat(body.get("normalBalance").asString()).isEqualTo(List.of("ASSET","EXPENSE").contains(type)?"DEBIT":"CREDIT");
            assertThat(body.has("balance")).isFalse();assertThat(body.has("createdBy")).isFalse();
            var read=get(response.headers().firstValue("Location").orElseThrow(),token);
            assertThat(read.statusCode()).isEqualTo(200);assertThat(read.body()).isEqualTo(response.body());
        }
        counts(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_accounts WHERE created_by='wallet-service' AND integrator_id='partner-a'",Integer.class)).isEqualTo(5);
    }
    @Test void replaysCanonicalRequestsButRejectsDifferentDetailsAndActors() throws Exception {
        String token=service("partner-a");var first=post("CUSTOMER_FUNDS","LIABILITY","stable",token);
        var replay=post("customer_funds","LIABILITY","stable",token);
        assertThat(replay.statusCode()).isEqualTo(201);assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        for(var response:List.of(post("OTHER","LIABILITY","stable",token),post("CUSTOMER_FUNDS","ASSET","stable",token),
                post("CUSTOMER_FUNDS","LIABILITY","stable",auth("partner-a",SCOPES,"another-service","service")))) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        }
        counts(1);
    }
    @Test void preventsDuplicateCodesUnderDifferentKeysWithoutMutatingTheirClassification() throws Exception {
        String token=service("partner-a");assertThat(post("CASH","ASSET","first",token).statusCode()).isEqualTo(201);
        var duplicate=post("CASH","LIABILITY","second",token);assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).get("code").asString()).isEqualTo("ACCOUNT_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT account_type FROM ledger_accounts",String.class)).isEqualTo("ASSET");counts(1);
    }
    @Test void isolatesTenantKeyNamespacesAndHidesForeignOrMissingAccounts() throws Exception {
        var first=post("CASH","ASSET","same",service("partner-a"));
        assertThat(post("CASH","ASSET","same",service("partner-b")).statusCode()).isEqualTo(201);
        String path=first.headers().firstValue("Location").orElseThrow();
        assertThat(get(path,service("partner-b")).statusCode()).isEqualTo(404);
        assertThat(get("/internal/v1/accounts/"+UUID.randomUUID(),service("partner-a")).statusCode()).isEqualTo(404);counts(2);
    }
    @Test void requiresEndpointScopesAndTrustedServiceIdentity() throws Exception {
        assertThat(post("CASH","ASSET","key",null).statusCode()).isEqualTo(401);
        for(String token:List.of(auth("partner-a","ledger:accounts:read","wallet-service","service"),
                auth("partner-a",SCOPES,"operator","operator"),auth("partner-a",SCOPES,null,"service"),
                auth("partner-a",SCOPES," ","service")))
            assertThat(post("CASH","ASSET","key",token).statusCode()).isEqualTo(403);
        var response=post("CASH","ASSET","key",service("partner-a"));String path=response.headers().firstValue("Location").orElseThrow();
        assertThat(get(path,auth("partner-a","ledger:accounts:write","wallet-service","service")).statusCode()).isEqualTo(403);
        assertThat(get(path,auth("partner-a","ledger:accounts:read","operator","operator")).statusCode()).isEqualTo(403);counts(1);
    }
    @Test void rejectsTokensForAnotherAudienceBeforeAllowingMetadataAccess() throws Exception {
        var existing=SignedJWT.parse(service("partner-a")).getJWTClaimsSet();
        var claims=new JWTClaimsSet.Builder(existing).audience("boodoo-wallet-api").build();
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims);
        token.sign(new RSASSASigner(KEY));
        assertThat(post("CASH","ASSET","key",token.serialize()).statusCode()).isEqualTo(401);counts(0);
    }
    @Test void validatesAccountDetailsAndRejectsClientProvidedBalanceOrNormalSide() throws Exception {
        String token=service("partner-a");
        for(String body:List.of("{}","{broken","{\"code\":\"CASH\",\"currency\":\"EUR\",\"type\":\"ASSET\"}",
                "{\"code\":\"CASH\",\"currency\":\"XOF\",\"type\":\"OTHER\"}",
                "{\"code\":\"CASH\",\"currency\":\"XOF\",\"type\":\"ASSET\",\"balance\":100}",
                "{\"code\":\"CASH\",\"currency\":\"XOF\",\"type\":\"ASSET\",\"normalBalance\":\"CREDIT\"}"))
            assertThat(raw(body,"key",token).statusCode()).isEqualTo(400);
        assertThat(post("invalid code","ASSET","key",token).statusCode()).isEqualTo(400);
        assertThat(post("CASH","ASSET",null,token).statusCode()).isEqualTo(400);
        assertThat(post("CASH","ASSET","invalid key",token).statusCode()).isEqualTo(400);
        assertThat(get("/internal/v1/accounts/invalid",token).statusCode()).isEqualTo(400);counts(0);
    }
    @Test void concurrentSameKeyRequestsReturnOneAccountAndOneImmutableSnapshot() throws Exception {
        String token=service("partner-a");var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for(int n=0;n<6;n++)futures.add(pool.submit(()->{start.await();return post("CASH","ASSET","same",token);}));
            start.countDown();Set<String> bodies=new HashSet<>();int original=0;
            for(var future:futures){var response=future.get(20,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);bodies.add(response.body());
                if(response.headers().firstValue("Idempotency-Replayed").orElseThrow().equals("false"))original++;
            }
            assertThat(original).isEqualTo(1);assertThat(bodies).hasSize(1);
        }
        counts(1);
    }
    @Test void concurrentDifferentKeysCannotBypassCodeUniqueness() throws Exception {
        String token=service("partner-a");var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{start.await();return post("CASH","ASSET","one",token).statusCode();});
            var b=pool.submit(()->{start.await();return post("CASH","ASSET","two",token).statusCode();});start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(201,409);
        }
        counts(1);
    }
    @Test void persistenceFailureRollsBackBothAccountAndRequestThenAllowsSafeRetry() throws Exception {
        String token=service("partner-a");
        jdbc.execute("CREATE FUNCTION reject_account_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_account_test BEFORE INSERT ON account_creation_requests FOR EACH ROW EXECUTE FUNCTION reject_account_test()");
        try {
            var response=post("CASH","ASSET","retry",token);assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("Injected failure","INSERT","SQLException");counts(0);
        } finally {
            jdbc.execute("DROP TRIGGER reject_account_test ON account_creation_requests");jdbc.execute("DROP FUNCTION reject_account_test()");
        }
        assertThat(post("CASH","ASSET","retry",token).statusCode()).isEqualTo(201);counts(1);
    }
    @Test void databasePreventsAccountHistoryMutationAndInconsistentNormalSides() throws Exception {
        String token=service("partner-a");post("CASH","ASSET","key",token);
        for(String sql:List.of("UPDATE ledger_accounts SET account_type='EXPENSE'","DELETE FROM ledger_accounts", "DELETE FROM account_creation_requests"))
            org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("INSERT INTO ledger_accounts(id,integrator_id,code,currency,account_type,normal_balance,created_by) VALUES (?,'partner-a','INVALID','XOF','ASSET','CREDIT','service')",UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);counts(1);
    }

}
