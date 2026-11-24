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
class ReversalTests {
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

    private HttpResponse<String> operation(String path,String body,String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void independentApprovalPostsExactlyOneLinkedCompensation() throws Exception {
        UUID cash=account("CASH","ASSET"),wallet=account("WALLET","LIABILITY");
        var posted=journal(UUID.randomUUID(),cash,wallet,100,"fund",journalToken("partner-a"));String original=json.readTree(posted.body()).get("id").asString();
        String maker=auth("partner-a","ledger:reversals:request ledger:reversals:approve","maker","operator"),checker=auth("partner-a","ledger:reversals:approve","checker","operator");
        var request=operation("/internal/v1/journals/"+original+"/reversal","{\"reasonCode\":\"DUPLICATE_SETTLEMENT\"}",maker);assertThat(request.statusCode()).isEqualTo(201);
        String id=json.readTree(request.body()).get("id").asString(),path="/internal/v1/reversals/"+id+"/approval";
        assertThat(operation(path,"",maker).statusCode()).isEqualTo(403);
        assertThat(operation(path,"",auth("partner-b","ledger:reversals:approve","checker","operator")).statusCode()).isEqualTo(404);
        var approved=operation(path,"",checker);assertThat(approved.statusCode()).isEqualTo(200);assertThat(operation(path,"",checker).body()).isEqualTo(approved.body());
        assertThat(balance(cash)).isZero();assertThat(balance(wallet)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM journals",Integer.class)).isEqualTo(2);
    }
    @Test void spentFundsBlockReversalAndApprovalRemainsPending() throws Exception {
        UUID cash=account("CASH","ASSET"),wallet=account("WALLET","LIABILITY"),other=account("OTHER","LIABILITY");
        var posted=journal(UUID.randomUUID(),cash,wallet,100,"fund",journalToken("partner-a"));journal(UUID.randomUUID(),wallet,other,90,"spend",journalToken("partner-a"));
        var request=operation("/internal/v1/journals/"+json.readTree(posted.body()).get("id").asString()+"/reversal","{\"reasonCode\":\"ERROR\"}",auth("partner-a","ledger:reversals:request","maker","operator"));
        var approval=operation("/internal/v1/reversals/"+json.readTree(request.body()).get("id").asString()+"/approval","",auth("partner-a","ledger:reversals:approve","checker","operator"));assertThat(approval.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_reversals WHERE approved_by IS NOT NULL",Integer.class)).isZero();assertThat(balance(wallet)).isEqualTo(10);
    }
}
