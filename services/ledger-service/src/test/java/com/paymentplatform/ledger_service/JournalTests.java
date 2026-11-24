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
class JournalTests {
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
        jdbc.execute("TRUNCATE journal_requests,journal_entries,journals,account_creation_requests,ledger_accounts CASCADE");
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
    private String journalToken(String tenant) throws Exception {return auth(tenant,SCOPES+" ledger:journals:write ledger:journals:read ledger:balances:read ledger:reconciliation:read","wallet-service","service");}
    private UUID account(String code,String type) throws Exception {return UUID.fromString(json.readTree(post(code,type,code,service("partner-a")).body()).get("id").asString());}
    private HttpResponse<String> journal(UUID reference,UUID debit,UUID credit,long amount,String key,String token) throws Exception {
        return journalBody(json.writeValueAsString(Map.of("reference",reference,"entries",List.of(Map.of("accountId",debit,"side","DEBIT","amount",amount),Map.of("accountId",credit,"side","CREDIT","amount",amount)))),key,token);
    }
    private HttpResponse<String> journalBody(String body,String key,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/journals")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(key!=null)builder.header("Idempotency-Key",key);if(token!=null)builder.header("Authorization","Bearer "+token);
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private long balance(UUID id) throws Exception {var response=get("/internal/v1/accounts/"+id+"/balance",journalToken("partner-a"));assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body()).get("amountMinorUnits").asLong();}
    @Test void postsBalancedFundingAndTransfersWithAuthoritativeNormalBalances() throws Exception {
        UUID cash=account("CASH","ASSET"),sender=account("SENDER","LIABILITY"),recipient=account("RECIPIENT","LIABILITY");String token=journalToken("partner-a");
        var funding=journal(UUID.randomUUID(),cash,sender,100,"fund",token);assertThat(funding.statusCode()).isEqualTo(201);
        assertThat(journal(UUID.randomUUID(),sender,recipient,80,"send",token).statusCode()).isEqualTo(201);
        assertThat(balance(cash)).isEqualTo(100);assertThat(balance(sender)).isEqualTo(20);assertThat(balance(recipient)).isEqualTo(80);
        var read=get("/internal/v1/journals/"+json.readTree(funding.body()).get("id").asString(),token);assertThat(read.body()).isEqualTo(funding.body());
        assertThat(read.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void canonicalRetriesNeverPostTwiceAndReferenceLookupResolvesUnknownOutcomes() throws Exception {
        UUID a=account("CASH","ASSET"),b=account("FUNDS","LIABILITY"),ref=UUID.randomUUID();String token=journalToken("partner-a");
        var first=journal(ref,a,b,100,"same",token);var replay=journal(ref,a,b,100,"same",token);
        assertThat(replay.statusCode()).isEqualTo(201);assertThat(replay.body()).isEqualTo(first.body());assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(journal(ref,a,b,100,"other",token).statusCode()).isEqualTo(409);
        assertThat(journal(ref,a,b,101,"same",token).statusCode()).isEqualTo(409);
        assertThat(get("/internal/v1/journals/by-reference?reference="+ref,token).body()).isEqualTo(first.body());assertThat(balance(a)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals",Integer.class)).isEqualTo(1);
    }
    @Test void preventsOverdraftAndConcurrentDoubleSpending() throws Exception {
        UUID cash=account("CASH","ASSET"),source=account("SOURCE","LIABILITY"),a=account("A","LIABILITY"),b=account("B","LIABILITY");String token=journalToken("partner-a");
        journal(UUID.randomUUID(),cash,source,100,"fund",token);var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{start.await();return journal(UUID.randomUUID(),source,a,80,"one",token).statusCode();});
            var two=pool.submit(()->{start.await();return journal(UUID.randomUUID(),source,b,80,"two",token).statusCode();});start.countDown();
            assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(balance(source)).isEqualTo(20);assertThat(balance(a)+balance(b)).isEqualTo(80);
    }
    @Test void rejectsUnbalancedOrDuplicateEntriesAndInvalidAmounts() throws Exception {
        UUID a=account("CASH","ASSET"),b=account("FUNDS","LIABILITY");String token=journalToken("partner-a");
        var body=Map.of("reference",UUID.randomUUID(),"entries",List.of(Map.of("accountId",a,"side","DEBIT","amount",10),Map.of("accountId",b,"side","CREDIT","amount",9)));
        assertThat(journalBody(json.writeValueAsString(body),"unbalanced",token).statusCode()).isEqualTo(400);
        assertThat(journal(UUID.randomUUID(),a,a,10,"duplicate",token).statusCode()).isEqualTo(400);
        assertThat(journal(UUID.randomUUID(),a,b,0,"zero",token).statusCode()).isEqualTo(400);
        assertThat(journal(UUID.randomUUID(),a,b,-1,"negative",token).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals",Integer.class)).isZero();
    }
    @Test void rejectsUnknownOrForeignAccountsAndUntrustedActors() throws Exception {
        UUID a=account("CASH","ASSET"),b=account("FUNDS","LIABILITY");String token=journalToken("partner-a");
        assertThat(journal(UUID.randomUUID(),a,UUID.randomUUID(),10,"unknown",token).statusCode()).isEqualTo(404);
        assertThat(journal(UUID.randomUUID(),a,b,10,"foreign",journalToken("partner-b")).statusCode()).isEqualTo(404);
        assertThat(journal(UUID.randomUUID(),a,b,10,"no-scope",service("partner-a")).statusCode()).isEqualTo(403);
        assertThat(journal(UUID.randomUUID(),a,b,10,"operator",auth("partner-a","ledger:journals:write","operator","operator")).statusCode()).isEqualTo(403);
        assertThat(journal(UUID.randomUUID(),a,b,10,"no-auth",null).statusCode()).isEqualTo(401);
    }
    @Test void databaseRejectsUnbalancedAndNegativeJournalsAtCommitAndPreservesHistory() throws Exception {
        UUID a=account("CASH","ASSET"),b=account("FUNDS","LIABILITY");String token=journalToken("partner-a");
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->tx.executeWithoutResult(status->{UUID id=UUID.randomUUID();jdbc.update("INSERT INTO journals VALUES (?,'partner-a',?,'service',CURRENT_TIMESTAMP)",id,UUID.randomUUID());jdbc.update("INSERT INTO journal_entries VALUES ('partner-a',?,?,'DEBIT',10)",id,a);})).isInstanceOf(org.springframework.transaction.TransactionSystemException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals",Integer.class)).isZero();
        journal(UUID.randomUUID(),a,b,10,"valid",token);
        for(String sql:List.of("DELETE FROM journal_entries","DELETE FROM journals","DELETE FROM journal_requests"))org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void rollsBackAllEntriesIfSnapshotPersistenceFails() throws Exception {
        UUID a=account("CASH","ASSET"),b=account("FUNDS","LIABILITY");String token=journalToken("partner-a");UUID ref=UUID.randomUUID();
        jdbc.execute("CREATE FUNCTION fail_journal_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_journal_test BEFORE INSERT ON journal_requests FOR EACH ROW EXECUTE FUNCTION fail_journal_test()");
        try{var response=journal(ref,a,b,10,"retry",token);assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).doesNotContain("Injected failure");assertThat(balance(a)).isZero();}
        finally{jdbc.execute("DROP TRIGGER fail_journal_test ON journal_requests");jdbc.execute("DROP FUNCTION fail_journal_test()");}
        assertThat(journal(ref,a,b,10,"retry",token).statusCode()).isEqualTo(201);assertThat(balance(a)).isEqualTo(10);
    }
    @Test void rejectsOverflowAndReportsConsistentReconciliationSnapshot() throws Exception {
        UUID a=account("CASH","ASSET"),b=account("FUNDS","LIABILITY");String token=journalToken("partner-a");
        assertThat(journal(UUID.randomUUID(),a,b,Long.MAX_VALUE,"maximum",token).statusCode()).isEqualTo(201);
        assertThat(journal(UUID.randomUUID(),a,b,1,"overflow",token).statusCode()).isEqualTo(409);assertThat(balance(a)).isEqualTo(Long.MAX_VALUE);
        var report=json.readTree(get("/internal/v1/reconciliation/snapshot",token).body());
        assertThat(report.get("journalCount").asLong()).isEqualTo(1);assertThat(report.get("unbalancedJournals").asLong()).isZero();assertThat(report.get("invalidBalances").asLong()).isZero();
        assertThat(get("/internal/v1/reconciliation/snapshot",service("partner-a")).statusCode()).isEqualTo(403);
    }

}
