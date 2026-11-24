package com.paymentplatform.reconciliation_service;

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
class ExternalStatementTests {
    private static final java.util.concurrent.atomic.AtomicInteger MODE=new java.util.concurrent.atomic.AtomicInteger(),CALLS=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> FORWARDED=new java.util.concurrent.atomic.AtomicReference<>();
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
        registry.add("boodoo.dependencies.origins.ledger",()->"http://127.0.0.1:"+KEYS.getAddress().getPort());
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-reconciliation-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE statement_investigations,external_statements,reconciliation_reports");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-reconciliation-api")
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
            server.createContext("/internal/v1/reconciliation/snapshot",exchange->{
                CALLS.incrementAndGet();FORWARDED.set(exchange.getRequestHeaders().getFirst("Authorization"));int mode=MODE.get();
                String value=mode==3?"x".repeat(70000):"{\"journalCount\":2,\"unbalancedJournals\":"+(mode==1?1:0)+",\"invalidBalances\":0,\"checkedAt\":\""+(mode==4?Instant.EPOCH:Instant.now())+"\"}";
                byte[] snapshotBytes=value.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(mode==2?503:200,snapshotBytes.length);try(var out=exchange.getResponseBody()){out.write(snapshotBytes);}
            });
            server.createContext("/internal/v1/accounts/",x->{
                var query=new HashMap<String,String>();for(String part:x.getRequestURI().getQuery().split("&")){var pair=part.split("=",2);query.put(pair[0],pair[1]);}
                String account=x.getRequestURI().getPath().split("/")[4];var mapper=new ObjectMapper();var result=mapper.createObjectNode();result.put("accountId",account);result.put("currency","XOF");result.put("from",query.get("from"));result.put("to",query.get("to"));result.put("opening",0);result.put("closing",MODE.get()==2?101:100);result.put("entryCount",1);result.put("complete",MODE.get()!=1);result.put("checkedAt",Instant.now().toString());var entries=result.putArray("entries");var entry=entries.addObject();entry.put("reference",new UUID(0,901).toString());entry.put("side","DEBIT");entry.put("amount",MODE.get()==2?101:100);byte[] bytes=mapper.writeValueAsBytes(result);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}
            });
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

 private String token()throws Exception{return service("partner-a","reconciliation:statements:write reconciliation:statements:read");}
 private String body(UUID id,String statement){return json.writeValueAsString(Map.of("id",id,"accountId",new UUID(0,900),"provider","BANK_TEST","statementReference",statement,"from",Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),"to",Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),"opening",0,"closing",100,"entries",List.of(Map.of("externalId","LINE_001","reference",new UUID(0,901),"side","DEBIT","amount",100))));}
 @Test void comparesCompleteReferencesAmountsAndBalancesAndPreservesImportedEvidence()throws Exception{
  UUID id=UUID.randomUUID();String body=body(id,"STATEMENT_001");MODE.set(0);var matched=post("/internal/v1/external-statements",body,null,token());assertThat(matched.statusCode()).isEqualTo(201);assertThat(json.readTree(matched.body()).get("status").asString()).isEqualTo("MATCHED");
  MODE.set(2);assertThat(post("/internal/v1/external-statements",body,null,token()).body()).isEqualTo(matched.body());
  var mismatch=post("/internal/v1/external-statements",body(UUID.randomUUID(),"STATEMENT_002"),null,token());assertThat(mismatch.statusCode()).isEqualTo(201);assertThat(json.readTree(mismatch.body()).get("status").asString()).isEqualTo("DISCREPANCY");
  assertThat(get("/internal/v1/external-statements/"+id,service("partner-b","reconciliation:statements:read")).statusCode()).isEqualTo(404);
 }
 @Test void incompleteLedgerSnapshotNeverPassesAndFractionalImportedMoneyIsRejected()throws Exception{
  MODE.set(1);String body=body(UUID.randomUUID(),"STATEMENT_003");var incomplete=post("/internal/v1/external-statements",body,null,token());assertThat(incomplete.statusCode()).isEqualTo(201);assertThat(json.readTree(incomplete.body()).get("status").asString()).isEqualTo("INCOMPLETE");
  assertThat(post("/internal/v1/external-statements",body(UUID.randomUUID(),"STATEMENT_004").replace("\"amount\":100","\"amount\":100.5"),null,token()).statusCode()).isEqualTo(400);
 }
}
