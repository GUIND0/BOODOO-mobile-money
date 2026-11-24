package com.paymentplatform.api_gateway;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeatureTests {
    private static final ObjectMapper PEER_JSON=new ObjectMapper();
    private static final java.util.concurrent.atomic.AtomicInteger MODE=new java.util.concurrent.atomic.AtomicInteger(),CALLS=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> TARGET=new java.util.concurrent.atomic.AtomicReference<>(),BODY=new java.util.concurrent.atomic.AtomicReference<>(),TOKEN=new java.util.concurrent.atomic.AtomicReference<>(),PATH=new java.util.concurrent.atomic.AtomicReference<>(),IF_MATCH=new java.util.concurrent.atomic.AtomicReference<>(),TENANT_HEADER=new java.util.concurrent.atomic.AtomicReference<>(),KEY_HEADER=new java.util.concurrent.atomic.AtomicReference<>();
    private static final Map<String,HttpServer> PEERS=peers();
    private static final String ISSUER = "https://directory-test.example";
    private static final RSAKey KEY = signingKey();
    private static final HttpServer KEYS = keys();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        for(var entry:PEERS.entrySet())registry.add("boodoo.dependencies.origins."+entry.getKey(),()->"http://127.0.0.1:"+entry.getValue().getAddress().getPort());
        registry.add("management.endpoints.web.exposure.include",()->"health,info,metrics");
        registry.add("boodoo.gateway.allowed-origins",()->"https://integrator.example");
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> "boodoo-gateway-api");
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void close() { HTTP.close(); KEYS.stop(0);PEERS.values().forEach(server->server.stop(0)); }

    private String auth(String tenant, String scope, String actor, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("boodoo-gateway-api")
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
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static Map<String,HttpServer> peers(){try{var peers=new HashMap<String,HttpServer>();for(String target:List.of("customer","identity","wallet","transfer","notification")){
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
            CALLS.incrementAndGet();TARGET.set(target);PATH.set(exchange.getRequestURI().toString());BODY.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));TOKEN.set(exchange.getRequestHeaders().getFirst("Authorization"));IF_MATCH.set(exchange.getRequestHeaders().getFirst("If-Match"));TENANT_HEADER.set(exchange.getRequestHeaders().getFirst("X-Integrator-Id"));KEY_HEADER.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Set-Cookie","upstream-session=must-not-leak");exchange.getResponseHeaders().set("ETag","\"3\"");exchange.getResponseHeaders().set("Location",MODE.get()==2?"https://must-not-follow.invalid":"/v1/customers/test");exchange.getResponseHeaders().set("Idempotency-Replayed","false");
            if(MODE.get()==3){exchange.sendResponseHeaders(204,-1);exchange.close();return;}
            byte[] bytes=(MODE.get()==1?"\""+"x".repeat(70000)+"\"":PEER_JSON.writeValueAsString(Map.of("target",target,"path",exchange.getRequestURI().toString()))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(MODE.get()==2?302:200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();peers.put(target,server);}return Map.copyOf(peers);}catch(Exception error){throw new IllegalStateException(error);}}
    @BeforeEach void clearPeer(){MODE.set(0);CALLS.set(0);TARGET.set(null);BODY.set(null);TOKEN.set(null);PATH.set(null);IF_MATCH.set(null);TENANT_HEADER.set(null);KEY_HEADER.set(null);}
    private String token()throws Exception{return auth("partner-a","customers:read customers:write pins:create pins:verify wallets:write transfers:write","integration-client",null);}
    @Test void routesPublicResourcesToTheirFixedOwnersWithUnchangedCallerToken()throws Exception{
        String token=token();for(var route:Map.of("/v1/customers","customer","/v1/customers/00000000-0000-0000-0000-000000000001/pin","identity","/v1/wallets/00000000-0000-0000-0000-000000000001","wallet","/v1/transfers","transfer","/v1/customers/00000000-0000-0000-0000-000000000001/notifications","notification").entrySet()){
            var response=get(route.getKey(),token);assertThat(response.statusCode()).isEqualTo(200);assertThat(TARGET.get()).isEqualTo(route.getValue());assertThat(TOKEN.get()).isEqualTo("Bearer "+token);assertThat(TENANT_HEADER.get()).isNull();assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }
    @Test void preservesPinStringsIdempotencyQueriesAndConditionalProfileHeaders()throws Exception{
        var pin=post("/v1/customers/00000000-0000-0000-0000-000000000001/pin","{\"pin\":\"012345\"}","pin-create-001",token());assertThat(pin.statusCode()).isEqualTo(200);assertThat(PEER_JSON.readTree(BODY.get()).get("pin").asString()).isEqualTo("012345");assertThat(KEY_HEADER.get()).isEqualTo("pin-create-001");
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/customers/00000000-0000-0000-0000-000000000001")).header("Authorization","Bearer "+token()).header("Content-Type","application/json").header("If-Match","\"3\"").method("PATCH",HttpRequest.BodyPublishers.ofString("{\"city\":\"Bamako\"}")).build();
        var patched=HTTP.send(request,HttpResponse.BodyHandlers.ofString());assertThat(patched.statusCode()).isEqualTo(200);assertThat(IF_MATCH.get()).isEqualTo("\"3\"");assertThat(patched.headers().firstValue("ETag")).contains("\"3\"");
        assertThat(get("/v1/customers/by-reference?externalReference=a%2Bb%23c",token()).statusCode()).isEqualTo(200);assertThat(PATH.get()).isEqualTo("/v1/customers/by-reference?externalReference=a%2Bb%23c");
    }
    @Test void requiresGatewayAudienceAndNeverExposesInternalControlRoutes()throws Exception{
        assertThat(get("/v1/customers",null).statusCode()).isEqualTo(401);
        String valid=token();var claims=new JWTClaimsSet.Builder(SignedJWT.parse(valid).getJWTClaimsSet()).audience("boodoo-customer-api").build();var wrong=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims);wrong.sign(new RSASSASigner(KEY));assertThat(get("/v1/customers",wrong.serialize()).statusCode()).isEqualTo(401);
        assertThat(get("/internal/v1/accounts",valid).statusCode()).isEqualTo(403);assertThat(get("/v1/unknown",valid).statusCode()).isEqualTo(404);assertThat(CALLS.get()).isZero();
        assertThat(get("/actuator/health",null).statusCode()).isEqualTo(200);
    }
    @Test void rejectsTraversalOversizedOrMalformedBodiesBeforeForwarding()throws Exception{
        String token=token();assertThat(get("/v1/customers/x%2ey",token).statusCode()).isIn(400,401,403,404);assertThat(get("/v1/customers/../../internal/v1/accounts",token).statusCode()).isIn(400,401,403,404);
        assertThat(post("/v1/customers","{broken","invalid",token).statusCode()).isEqualTo(400);assertThat(post("/v1/customers","\""+"x".repeat(70000)+"\"","large",token).statusCode()).isEqualTo(413);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v1/customers")).header("Authorization","Bearer "+token).header("Content-Type","text/plain").POST(HttpRequest.BodyPublishers.ofString("{}")).build();assertThat(HTTP.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);assertThat(CALLS.get()).isZero();
    }
    @Test void boundsUpstreamResponsesAndNeverFollowsRedirects()throws Exception{
        MODE.set(1);assertThat(get("/v1/customers",token()).statusCode()).isEqualTo(503);CALLS.set(0);MODE.set(2);assertThat(get("/v1/customers",token()).statusCode()).isEqualTo(503);assertThat(CALLS.get()).isEqualTo(1);
        MODE.set(3);var response=post("/v1/customers/00000000-0000-0000-0000-000000000001/pin/verify","{\"pin\":\"012345\"}",null,token());assertThat(response.statusCode()).isEqualTo(204);assertThat(response.body()).isEmpty();
    }
    @Test void allowsOnlyExplicitBrowserOriginsWithoutCookieCredentials()throws Exception{
        var uri=URI.create("http://localhost:"+port+"/v1/customers");
        for(String origin:List.of("https://integrator.example","https://untrusted.example")){
            var request=HttpRequest.newBuilder(uri).header("Origin",origin).header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Authorization,Content-Type,Idempotency-Key").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(origin.equals("https://integrator.example")?200:403);if(response.statusCode()==200){assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();}
        }
        assertThat(CALLS.get()).isZero();
    }

 @Test void routesMerchantPaymentsAndProtectsGlobalOperationalMetrics()throws Exception{
  assertThat(post("/v1/merchant-payments","{}","merchant",token()).statusCode()).isEqualTo(200);assertThat(TARGET.get()).isEqualTo("transfer");
  assertThat(get("/actuator/metrics",token()).statusCode()).isEqualTo(403);
  assertThat(get("/actuator/metrics",auth("partner-a","operations:metrics:read","integration-service","service")).statusCode()).isEqualTo(403);
  assertThat(get("/actuator/metrics",auth("partner-a","operations:metrics:read","metrics-collector","service")).statusCode()).isEqualTo(200);
 }

}
