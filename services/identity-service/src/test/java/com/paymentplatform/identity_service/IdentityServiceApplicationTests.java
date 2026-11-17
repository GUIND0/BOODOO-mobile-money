package com.paymentplatform.identity_service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paymentplatform.identity_service.api.CreatePinRequest;
import com.paymentplatform.identity_service.security.PinProtectionProperties;
import com.paymentplatform.identity_service.security.PinProtector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityServiceApplicationTests {
    private static final String ISSUER = "https://test-issuer.example";
    private static final String IDENTITY_AUDIENCE = "boodoo-identity-api";
    private static final String CUSTOMER_AUDIENCE = "boodoo-customer-api";
    private static final String SCOPES = "pins:create customers:ownership:read";
    private static final String BODY = "{\"pin\":\"739152\"}";
    private static final byte[] PEPPER = randomPepper();
    private static final RSAKey KEY = signingKey();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<UUID, String> OWNERS = new ConcurrentHashMap<>();
    private static final AtomicInteger CUSTOMER_CALLS = new AtomicInteger();
    private static final AtomicInteger UPSTREAM_STATUS = new AtomicInteger();
    private static final AtomicInteger UPSTREAM_DELAY = new AtomicInteger();
    private static final AtomicReference<String> FORWARDED_TOKEN = new AtomicReference<>();
    private static final HttpServer UPSTREAM = upstream();
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PinProtector protector;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/jwks");
        registry.add("boodoo.security.audience", () -> IDENTITY_AUDIENCE);
        registry.add("boodoo.security.customer-audience", () -> CUSTOMER_AUDIENCE);
        registry.add("boodoo.customers.base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        registry.add("boodoo.pin.pepper", () -> Base64.getEncoder().encodeToString(PEPPER));
        registry.add("boodoo.pin.pepper-version", () -> "test-v1");
    }

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE pin_creation_requests, customer_pins, pin_setup_attempts");
        OWNERS.clear(); OWNERS.put(CUSTOMER, "partner-a");
        UPSTREAM_STATUS.set(0); UPSTREAM_DELAY.set(0); CUSTOMER_CALLS.set(0); FORWARDED_TOKEN.set(null);
    }

    @AfterAll
    static void close() { HTTP.close(); UPSTREAM.stop(0); }

    @Test
    void startsWithMigrationAndPublicHealth() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
        var response = HTTP.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        assertThat(json.readTree(response.body()).has("components")).isFalse();
    }

    @Test
    void createsProtectedPinWithoutReturningSecretsAndRelaysPartnerToken() throws Exception {
        String auth = token("partner-a");
        var response = post(CUSTOMER, BODY, "first", auth);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).get("customerId").asString()).isEqualTo(CUSTOMER.toString());
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("CREATED");
        assertThat(response.body()).doesNotContain("739152", "argon2", "pepper", "pin_hash");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(FORWARDED_TOKEN.get()).isEqualTo(auth);
        String hash = jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class);
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        assertThat(encoder.matches(storageInput("partner-a", CUSTOMER, "739152"), hash)).isTrue();
        assertThat(encoder.matches("739152", hash)).isFalse();
        assertThat(jdbc.queryForObject("SELECT pepper_version FROM customer_pins", String.class)).isEqualTo("test-v1");
        assertCounts(1);
    }

    @Test
    void replaysSameRequestWithoutReplacingHashOrCreationTime() throws Exception {
        String auth = token("partner-a");
        var first = post(CUSTOMER, BODY, "stable", auth);
        String hash = jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class);
        var replay = post(CUSTOMER, BODY, "stable", auth);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class)).isEqualTo(hash);
        assertCounts(1);
    }

    @Test
    void rejectsChangedPinAndChangedCustomerForSameKey() throws Exception {
        String auth = token("partner-a");
        UUID other = UUID.randomUUID(); OWNERS.put(other, "partner-a");
        var first = post(CUSTOMER, BODY, "stable", auth);
        assertError(post(CUSTOMER, "{\"pin\":\"840271\"}", "stable", auth), 409, "IDEMPOTENCY_CONFLICT");
        assertError(post(other, BODY, "stable", auth), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(post(CUSTOMER, BODY, "stable", auth).body()).isEqualTo(first.body());
        assertCounts(1);
    }

    @Test
    void neverOverwritesAnExistingPinUnderANewKey() throws Exception {
        String auth = token("partner-a");
        assertThat(post(CUSTOMER, BODY, "first", auth).statusCode()).isEqualTo(201);
        String original = jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class);
        assertError(post(CUSTOMER, "{\"pin\":\"840271\"}", "another", auth), 409, "PIN_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class)).isEqualTo(original);
        assertCounts(1);
    }

    @Test
    void rejectsUnknownAndForeignCustomersWithoutWritingState() throws Exception {
        UUID foreign = UUID.randomUUID(); OWNERS.put(foreign, "partner-b");
        assertError(post(foreign, BODY, "foreign", token("partner-a")), 404, "CUSTOMER_NOT_FOUND");
        assertError(post(UUID.randomUUID(), BODY, "missing", token("partner-a")), 404, "CUSTOMER_NOT_FOUND");
        assertCounts(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_setup_attempts", Integer.class)).isZero();
    }

    @Test
    void rechecksOwnershipOnReplay() throws Exception {
        String auth = token("partner-a");
        assertThat(post(CUSTOMER, BODY, "first", auth).statusCode()).isEqualTo(201);
        OWNERS.remove(CUSTOMER);
        assertError(post(CUSTOMER, BODY, "first", auth), 404, "CUSTOMER_NOT_FOUND");
        assertCounts(1);
    }

    @Test
    void separatesPartnerKeysAndCustomerPins() throws Exception {
        UUID other = UUID.randomUUID(); OWNERS.put(other, "partner-b");
        assertThat(post(CUSTOMER, BODY, "same-key", token("partner-a")).statusCode()).isEqualTo(201);
        assertThat(post(other, BODY, "same-key", token("partner-b")).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForList("SELECT pin_hash FROM customer_pins", String.class)).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT request_hmac FROM pin_creation_requests", String.class)).doesNotHaveDuplicates();
        assertCounts(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{malformed", "{\"pin\":null}", "{\"pin\":739152}", "{\"pin\":true}",
            "{\"pin\":\"12345\"}", "{\"pin\":\"7391527\"}", "{\"pin\":\" 739152\"}", "{\"pin\":\"abcdef\"}",
            "{\"pin\":\"111111\"}", "{\"pin\":\"123456\"}", "{\"pin\":\"654321\"}", "{\"pin\":\"890123\"}",
            "{\"pin\":\"739152\",\"integratorId\":\"partner-b\"}"})
    void rejectsInvalidPinAndUnknownFieldsBeforeAnyOwnershipCall(String body) throws Exception {
        var response = post(CUSTOMER, body, "invalid", token("partner-a"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("739152", "111111", "654321");
        assertThat(CUSTOMER_CALLS.get()).isZero(); assertCounts(0);
    }

    @Test
    void preservesLeadingZerosAndRedactsRequestRepresentation() throws Exception {
        assertThat(post(CUSTOMER, "{\"pin\":\"083971\"}", "leading-zero", token("partner-a")).statusCode()).isEqualTo(201);
        String hash = jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class);
        assertThat(new Argon2PasswordEncoder(16,32,1,19456,2).matches(storageInput("partner-a", CUSTOMER, "083971"), hash)).isTrue();
        assertThat(new CreatePinRequest(json.readTree("\"083971\"")).toString()).doesNotContain("083971");
        assertThat(new PinProtectionProperties(Base64.getEncoder().encodeToString(PEPPER), "v1").toString())
                .doesNotContain(Base64.getEncoder().encodeToString(PEPPER));
    }

    @Test
    void validatesCustomerIdHeaderAndMediaType() throws Exception {
        String auth = token("partner-a");
        for (String key : List.of("", "bad key", "k".repeat(129))) {
            assertThat(post(CUSTOMER, BODY, key, auth).statusCode()).isEqualTo(400);
        }
        assertThat(post(CUSTOMER, BODY, null, auth).statusCode()).isEqualTo(400);
        var malformed = request(CUSTOMER, BODY, "valid", auth).uri(uri("/v1/customers/not-a-uuid/pin")).build();
        assertThat(HTTP.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        var media = HttpRequest.newBuilder(uri("/v1/customers/"+CUSTOMER+"/pin"))
                .header("Authorization", "Bearer "+auth).header("Idempotency-Key", "media")
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        assertThat(HTTP.send(media, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        assertThat(CUSTOMER_CALLS.get()).isZero(); assertCounts(0);
    }

    @Test
    void authenticatesSignatureIssuerBothAudiencesExpiryAndTenant() throws Exception {
        assertThat(post(CUSTOMER, BODY, "unauthenticated", null).statusCode()).isEqualTo(401);
        for (String invalid : List.of(
                signed(signingKey(), ISSUER, audiences(), "partner-a", SCOPES, Instant.now().plusSeconds(300)),
                signed(KEY, "https://wrong.example", audiences(), "partner-a", SCOPES, Instant.now().plusSeconds(300)),
                signed(KEY, ISSUER, List.of(CUSTOMER_AUDIENCE), "partner-a", SCOPES, Instant.now().plusSeconds(300)),
                signed(KEY, ISSUER, List.of(IDENTITY_AUDIENCE), "partner-a", SCOPES, Instant.now().plusSeconds(300)),
                signed(KEY, ISSUER, audiences(), null, SCOPES, Instant.now().plusSeconds(300)),
                signed(KEY, ISSUER, audiences(), List.of("partner-a"), SCOPES, Instant.now().plusSeconds(300)),
                signed(KEY, ISSUER, audiences(), "partner-a", SCOPES, Instant.now().minusSeconds(300)),
                signed(KEY, ISSUER, audiences(), "partner-a", SCOPES, null))) {
            assertThat(post(CUSTOMER, BODY, "invalid-token", invalid).statusCode()).isEqualTo(401);
        }
        String readOnly = signed(KEY, ISSUER, audiences(), "partner-a", "pins:read", Instant.now().plusSeconds(300));
        assertThat(post(CUSTOMER, BODY, "no-write-scope", readOnly).statusCode()).isEqualTo(403);
        assertThat(CUSTOMER_CALLS.get()).isZero(); assertCounts(0);
    }

    @Test
    void requiresCustomerLookupPermissionAndNeverTrustsHeaders() throws Exception {
        String insufficient = signed(KEY, ISSUER, audiences(), "partner-a", "pins:create", Instant.now().plusSeconds(300));
        assertError(post(CUSTOMER, BODY, "no-lookup-scope", insufficient), 403, "OWNERSHIP_FORBIDDEN");
        var spoof = request(CUSTOMER, BODY, "spoof", token("partner-b")).header("X-Integrator-Id", "partner-a").build();
        assertThat(HTTP.send(spoof, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertCounts(0);
    }

    @Test
    void refusesCreationWhenOwnershipServiceFailsOrRedirects() throws Exception {
        for (int status : List.of(500, 503, 301, 401)) {
            UPSTREAM_STATUS.set(status);
            assertError(post(CUSTOMER, BODY, "upstream-failure", token("partner-a")), 503, "OWNERSHIP_UNAVAILABLE");
        }
        assertCounts(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_setup_attempts", Integer.class)).isZero();
    }

    @Test
    void timesOutOwnershipWithoutPersistingAPin() throws Exception {
        UPSTREAM_DELAY.set(4000);
        assertError(post(CUSTOMER, BODY, "timeout", token("partner-a")), 503, "OWNERSHIP_UNAVAILABLE");
        assertCounts(0);
    }

    @Test
    void boundsReplayGuessingEvenWhenConflictsRollback() throws Exception {
        String auth = token("partner-a");
        assertThat(post(CUSTOMER, BODY, "stable", auth).statusCode()).isEqualTo(201);
        for (int i = 0; i < 9; i++) assertThat(post(CUSTOMER, "{\"pin\":\"840271\"}", "stable", auth).statusCode()).isEqualTo(409);
        var limited = post(CUSTOMER, BODY, "stable", auth);
        assertError(limited, 429, "PIN_SETUP_RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).contains("900");
        assertThat(jdbc.queryForObject("SELECT attempts FROM pin_setup_attempts", Integer.class)).isEqualTo(10);
        jdbc.update("UPDATE pin_setup_attempts SET window_started_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'");
        assertThat(post(CUSTOMER, BODY, "stable", auth).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT attempts FROM pin_setup_attempts", Integer.class)).isEqualTo(1);
    }

    @Test
    void serializesConcurrentRetriesAndRejectsConcurrentReplacement() throws Exception {
        String auth = token("partner-a");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i=0;i<8;i++) futures.add(pool.submit(() -> { start.await(); return post(CUSTOMER, BODY, "concurrent", auth); }));
            start.countDown(); var results = new ArrayList<HttpResponse<String>>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            assertThat(results).allSatisfy(result -> assertThat(result.statusCode()).isEqualTo(201));
            assertThat(results.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1); assertCounts(1);
        }
        assertThat(post(CUSTOMER, BODY, "different", auth).statusCode()).isEqualTo(409);
    }

    @Test
    void concurrentDifferentKeysCannotCreateTwoPins() throws Exception {
        String auth=token("partner-a"); CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=pool.submit(() -> {start.await();return post(CUSTOMER,BODY,"one",auth);});
            var two=pool.submit(() -> {start.await();return post(CUSTOMER,"{\"pin\":\"840271\"}","two",auth);});
            start.countDown();
            assertThat(List.of(one.get(20,TimeUnit.SECONDS).statusCode(),two.get(20,TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201,409); assertCounts(1);
        }
    }

    @Test
    void enforcesRateLimitAtomicallyUnderConcurrency() throws Exception {
        String auth=token("partner-a"); CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<12;i++) futures.add(pool.submit(() -> {start.await();return post(CUSTOMER,BODY,"rate",auth).statusCode();}));
            start.countDown(); var codes=new ArrayList<Integer>();
            for(var future:futures)codes.add(future.get(20,TimeUnit.SECONDS));
            assertThat(codes.stream().filter(c -> c==201).count()).isEqualTo(10);
            assertThat(codes.stream().filter(c -> c==429).count()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT attempts FROM pin_setup_attempts",Integer.class)).isEqualTo(10);assertCounts(1);
        }
    }

    @Test
    void rollsBackPinAndIdempotencyTogetherButKeepsAttemptBudget() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_pin_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_pin_test BEFORE INSERT ON pin_creation_requests FOR EACH ROW EXECUTE FUNCTION reject_pin_test()");
        String auth=token("partner-a");
        try {
            var response=post(CUSTOMER,BODY,"rollback",auth);assertError(response,503,"PIN_CREATION_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("739152","Injected test failure");assertCounts(0);
            assertThat(jdbc.queryForObject("SELECT attempts FROM pin_setup_attempts",Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER reject_pin_test ON pin_creation_requests");jdbc.execute("DROP FUNCTION reject_pin_test()");
        }
        assertThat(post(CUSTOMER,BODY,"rollback",auth).statusCode()).isEqualTo(201);assertCounts(1);
    }

    @Test
    void databaseDisallowsMutationAndProtectionKeysHaveNoUnsafeDefault() throws Exception {
        assertThat(post(CUSTOMER,BODY,"immutable",token("partner-a")).statusCode()).isEqualTo(201);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM customer_pins")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE customer_pins SET pepper_version='other'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pin_creation_requests")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> new PinProtector(new PinProtectionProperties("invalid", "v1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PinProtector(new PinProtectionProperties(Base64.getEncoder().encodeToString(new byte[16]), "v1")))
                .isInstanceOf(IllegalArgumentException.class);assertCounts(1);
    }

    @Test
    void unavailablePepperVersionDoesNotBecomeAMisleadingPayloadConflict() throws Exception {
        jdbc.update("INSERT INTO customer_pins (integrator_id,customer_id,pin_hash,pepper_version) VALUES (?,?,?,?)",
                "partner-a",CUSTOMER,protector.encode("partner-a",CUSTOMER,"739152"),"retired");
        jdbc.update("INSERT INTO pin_creation_requests VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                "partner-a","retired",CUSTOMER,protector.fingerprint("partner-a",CUSTOMER,"739152"),"retired");
        assertError(post(CUSTOMER,BODY,"retired",token("partner-a")),503,"PIN_KEY_UNAVAILABLE");assertCounts(1);
    }

    private void assertCounts(int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_pins",Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_creation_requests",Integer.class)).isEqualTo(count);
    }
    private void assertError(HttpResponse<String> result,int status,String code) {
        assertThat(result.statusCode()).isEqualTo(status);
        assertThat(json.readTree(result.body()).get("code").asString()).isEqualTo(code);
    }
    private URI uri(String path) {return URI.create("http://localhost:"+port+path);}
    private HttpRequest.Builder request(UUID customer,String body,String key,String token) {
        var request=HttpRequest.newBuilder(uri("/v1/customers/"+customer+"/pin")).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(key!=null)request.header("Idempotency-Key",key);
        if(token!=null)request.header("Authorization","Bearer "+token);return request;
    }
    private HttpResponse<String> post(UUID customer,String body,String key,String token)throws Exception {
        return HTTP.send(request(customer,body,key,token).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String token(String partner)throws Exception {return signed(KEY,ISSUER,audiences(),partner,SCOPES,Instant.now().plusSeconds(300));}
    private static List<String> audiences(){return List.of(IDENTITY_AUDIENCE,CUSTOMER_AUDIENCE);}
    private static String signed(RSAKey key,String issuer,List<String> audiences,Object partner,String scope,Instant expiry)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(audiences).subject("integration-client").claim("scope",scope).issueTime(new Date());
        if(partner!=null)claims.claim("integrator_id",partner);if(expiry!=null)claims.expirationTime(Date.from(expiry));
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),claims.build());token.sign(new RSASSASigner(key));return token.serialize();
    }
    private static RSAKey signingKey(){try{return new RSAKeyGenerator(2048).keyID("test-key").generate();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static byte[] randomPepper(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return bytes;}
    private static String storageInput(String partner,UUID customer,String pin)throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(PEPPER,"HmacSHA256"));
        for(String value:List.of("boodoo-pin-storage-v1",partner,customer.toString(),pin)) {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());mac.update(bytes);
        }
        return Base64.getEncoder().encodeToString(mac.doFinal());
    }
    private static HttpServer upstream(){
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory()));
            server.createContext("/jwks",exchange->{byte[] bytes=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}});
            server.createContext("/internal/v1/customers/",exchange->{
                CUSTOMER_CALLS.incrementAndGet();int status=500;
                try {
                    if(UPSTREAM_DELAY.get()>0)Thread.sleep(UPSTREAM_DELAY.get());
                    String bearer=exchange.getRequestHeaders().getFirst("Authorization");String token=bearer.substring(7);FORWARDED_TOKEN.set(token);
                    var claims=SignedJWT.parse(token).getJWTClaimsSet();String path=exchange.getRequestURI().getPath();
                    UUID id=UUID.fromString(path.split("/")[4]);
                    if(!exchange.getRequestMethod().equals("GET")||!path.endsWith("/ownership"))status=400;
                    else if(!claims.getStringClaim("scope").contains("customers:ownership:read"))status=403;
                    else if(UPSTREAM_STATUS.get()!=0)status=UPSTREAM_STATUS.get();
                    else status=claims.getStringClaim("integrator_id").equals(OWNERS.get(id))?204:404;
                    exchange.sendResponseHeaders(status,-1);
                }catch(Exception ignored){}finally{exchange.close();}
            });
            server.start();return server;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
}
