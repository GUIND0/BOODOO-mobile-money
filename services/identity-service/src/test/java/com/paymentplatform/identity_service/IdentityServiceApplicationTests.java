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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE pin_verification_state, pin_creation_requests, customer_pins, pin_setup_attempts");
        OWNERS.clear(); OWNERS.put(CUSTOMER, "partner-a");
        UPSTREAM_STATUS.set(0); UPSTREAM_DELAY.set(0); CUSTOMER_CALLS.set(0); FORWARDED_TOKEN.set(null);
    }

    @AfterAll
    static void close() { HTTP.close(); UPSTREAM.stop(0); }

    @Test
    void startsWithMigrationAndPublicHealth() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(2);
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
    void boundsReplayGuessingAcrossConflictingCreationRequests() throws Exception {
        String auth = token("partner-a");
        assertThat(post(CUSTOMER, BODY, "stable", auth).statusCode()).isEqualTo(201);
        for (int i = 0; i < 4; i++) assertThat(post(CUSTOMER, "{\"pin\":\"840271\"}", "stable", auth).statusCode()).isEqualTo(409);
        var limited = post(CUSTOMER, "{\"pin\":\"840271\"}", "stable", auth);
        assertError(limited, 429, "PIN_VERIFICATION_LOCKED");
        assertThat(limited.headers().firstValue("Retry-After")).contains("900");
        assertThat(jdbc.queryForObject("SELECT attempts FROM pin_setup_attempts", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM pin_verification_state", Integer.class)).isEqualTo(5);
        assertThat(post(CUSTOMER, BODY, "stable", auth).statusCode()).isEqualTo(429);
        jdbc.update("UPDATE pin_setup_attempts SET window_started_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'");
        jdbc.update("UPDATE pin_verification_state SET locked_until = CURRENT_TIMESTAMP - INTERVAL '1 second', rate_window_started_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'");
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

    @Test
    void verifiesPinWithoutReturningASecretOrChangingCredential() throws Exception {
        seedPin();
        String before = jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class);
        var response = verify(CUSTOMER, BODY, verificationToken("partner-a"));
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class)).isEqualTo(before);
        assertVerificationState(1, 0);
        assertCounts(1);
    }

    @Test
    void verificationPreservesLeadingZerosAndDoesNotApplyCreationStrengthRules() throws Exception {
        assertThat(post(CUSTOMER, "{\"pin\":\"083971\"}", "zero", token("partner-a")).statusCode()).isEqualTo(201);
        assertThat(verify(CUSTOMER, "{\"pin\":\"083971\"}", verificationToken("partner-a")).statusCode()).isEqualTo(204);
        assertError(verify(CUSTOMER, "{\"pin\":\"111111\"}", verificationToken("partner-a")), 422, "PIN_INVALID");
        assertThat(new com.paymentplatform.identity_service.api.VerifyPinRequest(json.readTree("\"083971\"")).toString())
                .doesNotContain("083971");
        assertThat(new com.paymentplatform.identity_service.pin.PinRepository.Credential("secret-hash", "v1").toString())
                .doesNotContain("secret-hash");
        assertVerificationState(2, 1);
    }

    @Test
    void failedVerificationsCommitAndFifthFailureLocksEvenCorrectPins() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        for (int i = 1; i <= 4; i++) {
            assertError(verify(CUSTOMER, "{\"pin\":\"840271\"}", auth), 422, "PIN_INVALID");
            assertVerificationState(i, i);
        }
        var locked = verify(CUSTOMER, "{\"pin\":\"840271\"}", auth);
        assertError(locked, 429, "PIN_VERIFICATION_LOCKED");
        assertThat(locked.headers().firstValue("Retry-After")).contains("900");
        assertError(verify(CUSTOMER, BODY, auth), 429, "PIN_VERIFICATION_LOCKED");
        assertVerificationState(5, 5);
        assertThat(jdbc.queryForObject("SELECT locked_until > clock_timestamp() FROM pin_verification_state", Boolean.class)).isTrue();
    }

    @Test
    void successfulVerificationResetsConsecutiveFailures() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        for (int i = 0; i < 4; i++) assertThat(verify(CUSTOMER, "{\"pin\":\"840271\"}", auth).statusCode()).isEqualTo(422);
        assertThat(verify(CUSTOMER, BODY, auth).statusCode()).isEqualTo(204);
        assertVerificationState(5, 0);
        for (int i = 0; i < 4; i++) assertThat(verify(CUSTOMER, "{\"pin\":\"840271\"}", auth).statusCode()).isEqualTo(422);
        assertVerificationState(9, 4);
    }

    @Test
    void lockExpiresWithoutChangingTheStoredPin() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        for (int i = 0; i < 5; i++) verify(CUSTOMER, "{\"pin\":\"840271\"}", auth);
        jdbc.update("UPDATE pin_verification_state SET locked_until = clock_timestamp() - INTERVAL '1 second'");
        assertThat(verify(CUSTOMER, BODY, auth).statusCode()).isEqualTo(204);
        assertVerificationState(6, 0);
        assertThat(jdbc.queryForObject("SELECT locked_until IS NULL FROM pin_verification_state", Boolean.class)).isTrue();
    }

    @Test
    void throttlesSuccessfulComparisonsAndResetsExpiredRateWindow() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        for (int i = 0; i < 30; i++) assertThat(verify(CUSTOMER, BODY, auth).statusCode()).isEqualTo(204);
        assertError(verify(CUSTOMER, BODY, auth), 429, "PIN_VERIFICATION_RATE_LIMITED");
        assertVerificationState(30, 0);
        jdbc.update("UPDATE pin_verification_state SET rate_window_started_at = clock_timestamp() - INTERVAL '16 minutes'");
        assertThat(verify(CUSTOMER, BODY, auth).statusCode()).isEqualTo(204);
        assertVerificationState(1, 0);
    }

    @Test
    void creationReplayAndVerificationShareFailureLockout() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        for (int i = 0; i < 2; i++) assertThat(verify(CUSTOMER, "{\"pin\":\"840271\"}", auth).statusCode()).isEqualTo(422);
        for (int i = 0; i < 2; i++) assertThat(post(CUSTOMER, "{\"pin\":\"840271\"}", "verification-seed", token("partner-a")).statusCode()).isEqualTo(409);
        assertVerificationState(4, 4);
        assertError(verify(CUSTOMER, "{\"pin\":\"840271\"}", auth), 429, "PIN_VERIFICATION_LOCKED");
        assertError(post(CUSTOMER, BODY, "verification-seed", token("partner-a")), 429, "PIN_VERIFICATION_LOCKED");
        assertError(verify(CUSTOMER, BODY, auth), 429, "PIN_VERIFICATION_LOCKED");
        assertVerificationState(5, 5);
    }

    @Test
    void verificationFailureBudgetCannotBeBypassedWithAnIdempotencyHeader() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        for (int i = 0; i < 5; i++) {
            var request = HttpRequest.newBuilder(uri("/v1/customers/" + CUSTOMER + "/pin/verify"))
                    .header("Authorization", "Bearer " + auth).header("Content-Type", "application/json")
                    .header("Idempotency-Key", "same-every-time")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"840271\"}")).build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            assertThat(status).isEqualTo(i < 4 ? 422 : 429);
        }
        assertVerificationState(5, 5);
    }

    @Test
    void distinguishesOwnedCustomerWithoutPinFromUnknownOrForeignCustomers() throws Exception {
        assertError(verify(CUSTOMER, BODY, verificationToken("partner-a")), 404, "PIN_NOT_CONFIGURED");
        assertError(verify(CUSTOMER, BODY, verificationToken("partner-b")), 404, "CUSTOMER_NOT_FOUND");
        assertError(verify(UUID.randomUUID(), BODY, verificationToken("partner-a")), 404, "CUSTOMER_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_verification_state", Integer.class)).isZero();
        assertCounts(0);
    }

    @Test
    void verificationRequiresItsOwnScopeAndFreshCustomerOwnership() throws Exception {
        seedPin(); CUSTOMER_CALLS.set(0);
        assertThat(verify(CUSTOMER, BODY, null).statusCode()).isEqualTo(401);
        assertThat(verify(CUSTOMER, BODY, token("partner-a")).statusCode()).isEqualTo(403);
        assertThat(CUSTOMER_CALLS.get()).isZero();
        String noLookup = signed(KEY, ISSUER, audiences(), "partner-a", "pins:verify", Instant.now().plusSeconds(300));
        assertError(verify(CUSTOMER, BODY, noLookup), 403, "OWNERSHIP_FORBIDDEN");
        OWNERS.remove(CUSTOMER);
        assertError(verify(CUSTOMER, BODY, verificationToken("partner-a")), 404, "CUSTOMER_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_verification_state", Integer.class)).isZero();
    }

    @Test
    void verificationValidatesTokenExpiryIssuerAndBothAudiences() throws Exception {
        String scope = "pins:verify customers:ownership:read";
        for (String invalid : List.of(
                signed(KEY, ISSUER, audiences(), "partner-a", scope, Instant.now().minusSeconds(300)),
                signed(KEY, "https://wrong.example", audiences(), "partner-a", scope, Instant.now().plusSeconds(300)),
                signed(KEY, ISSUER, List.of(IDENTITY_AUDIENCE), "partner-a", scope, Instant.now().plusSeconds(300)))) {
            assertThat(verify(CUSTOMER, BODY, invalid).statusCode()).isEqualTo(401);
        }
        assertThat(CUSTOMER_CALLS.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{malformed", "{\"pin\":null}", "{\"pin\":739152}", "{\"pin\":true}",
            "{\"pin\":\"73915\"}", "{\"pin\":\"7391527\"}", "{\"pin\":\" 739152\"}",
            "{\"pin\":\"abcdef\"}", "{\"pin\":\"739152\",\"integratorId\":\"partner-b\"}"})
    void verificationRejectsMalformedInputWithoutComparingOrEchoingPin(String body) throws Exception {
        var response = verify(CUSTOMER, body, verificationToken("partner-a"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("739152");
        assertThat(CUSTOMER_CALLS.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_verification_state", Integer.class)).isZero();
    }

    @Test
    void verificationValidatesCustomerUuidAndContentType() throws Exception {
        String auth = verificationToken("partner-a");
        var malformed = HttpRequest.newBuilder(uri("/v1/customers/not-a-uuid/pin/verify"))
                .header("Authorization", "Bearer " + auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        assertThat(HTTP.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        var media = HttpRequest.newBuilder(uri("/v1/customers/" + CUSTOMER + "/pin/verify"))
                .header("Authorization", "Bearer " + auth).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        assertThat(HTTP.send(media, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        assertThat(CUSTOMER_CALLS.get()).isZero();
    }

    @Test
    void ownershipFailuresDoNotPerformComparisonsOrResetFailures() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        assertThat(verify(CUSTOMER, "{\"pin\":\"840271\"}", auth).statusCode()).isEqualTo(422);
        for (int code : List.of(500, 503, 301, 401)) {
            UPSTREAM_STATUS.set(code);
            assertError(verify(CUSTOMER, BODY, auth), 503, "OWNERSHIP_UNAVAILABLE");
        }
        assertVerificationState(1, 1);
    }

    @Test
    void concurrentWrongPinsCannotRacePastTheFailureLimit() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                start.await(); return verify(CUSTOMER, "{\"pin\":\"840271\"}", auth).statusCode();
            }));
            start.countDown(); var codes = new ArrayList<Integer>();
            for (var future : futures) codes.add(future.get(20, TimeUnit.SECONDS));
            assertThat(codes.stream().filter(c -> c == 422).count()).isEqualTo(4);
            assertThat(codes.stream().filter(c -> c == 429).count()).isEqualTo(8);
            assertVerificationState(5, 5);
        }
    }

    @Test
    void concurrentCorrectPinsHaveAtomicCountersAndDoNotChangeTheCredential() throws Exception {
        seedPin(); String auth = verificationToken("partner-a");
        String original = jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> { start.await(); return verify(CUSTOMER, BODY, auth).statusCode(); }));
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertVerificationState(8, 0);
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM customer_pins", String.class)).isEqualTo(original);
        assertCounts(1);
    }

    @Test
    void storageFailureNeverReportsVerificationSuccessOrLeaksDiagnostics() throws Exception {
        seedPin();
        jdbc.execute("CREATE FUNCTION reject_verification_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected verification failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_verification_test BEFORE UPDATE ON pin_verification_state FOR EACH ROW EXECUTE FUNCTION reject_verification_test()");
        try {
            var response = verify(CUSTOMER, BODY, verificationToken("partner-a"));
            assertError(response, 503, "PIN_VERIFICATION_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("739152", "Injected verification failure");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_verification_state", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_verification_test ON pin_verification_state");
            jdbc.execute("DROP FUNCTION reject_verification_test()");
        }
        assertThat(verify(CUSTOMER, BODY, verificationToken("partner-a")).statusCode()).isEqualTo(204);
        assertVerificationState(1, 0);
    }

    @Test
    void missingPepperVersionDoesNotCountAsAnIncorrectPin() throws Exception {
        jdbc.update("INSERT INTO customer_pins (integrator_id,customer_id,pin_hash,pepper_version) VALUES (?,?,?,?)",
                "partner-a",CUSTOMER,protector.encode("partner-a",CUSTOMER,"739152"),"retired");
        assertError(verify(CUSTOMER, BODY, verificationToken("partner-a")), 503, "PIN_KEY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_verification_state", Integer.class)).isZero();
    }

    @Test
    void concurrentCreationReplaysAcrossCustomersDoNotExhaustTheSmallConnectionPool() throws Exception {
        String auth = token("partner-a");
        var customers = new ArrayList<UUID>();
        for (int i = 0; i < 8; i++) {
            UUID customer = UUID.randomUUID(); OWNERS.put(customer, "partner-a"); customers.add(customer);
            assertThat(post(customer, BODY, "pool-" + i, auth).statusCode()).isEqualTo(201);
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < customers.size(); i++) {
                UUID customer = customers.get(i); String key = "pool-" + i;
                futures.add(pool.submit(() -> { start.await(); return post(customer, BODY, key, auth).statusCode(); }));
            }
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertCounts(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pin_verification_state WHERE attempts = 1 AND failed_attempts = 0", Integer.class))
                .isEqualTo(8);
    }

    @Test
    void upgradesExistingV1PinsAndIdempotencyRecordsWithoutChangingThem() {
        String schema = "pin_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("1").load().migrate();
            String hash = protector.encode("partner-a", CUSTOMER, "739152");
            String fingerprint = protector.fingerprint("partner-a", CUSTOMER, "739152");
            jdbc.update("INSERT INTO " + schema + ".customer_pins (integrator_id, customer_id, pin_hash, pepper_version) VALUES (?, ?, ?, ?)",
                    "partner-a", CUSTOMER, hash, "test-v1");
            jdbc.update("INSERT INTO " + schema + ".pin_creation_requests (integrator_id, idempotency_key, customer_id, request_hmac, pepper_version) VALUES (?, ?, ?, ?, ?)",
                    "partner-a", "existing-key", CUSTOMER, fingerprint, "test-v1");
            org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT pin_hash FROM " + schema + ".customer_pins", String.class)).isEqualTo(hash);
            assertThat(jdbc.queryForObject("SELECT request_hmac FROM " + schema + ".pin_creation_requests", String.class)).isEqualTo(fingerprint);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".pin_verification_state", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version = '2' AND success", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void seedPin() throws Exception {
        assertThat(post(CUSTOMER, BODY, "verification-seed", token("partner-a")).statusCode()).isEqualTo(201);
    }

    private void assertVerificationState(int attempts, int failures) {
        assertThat(jdbc.queryForObject("SELECT attempts FROM pin_verification_state", Integer.class)).isEqualTo(attempts);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM pin_verification_state", Integer.class)).isEqualTo(failures);
    }

    private String verificationToken(String partner) throws Exception {
        return signed(KEY, ISSUER, audiences(), partner, "pins:verify customers:ownership:read", Instant.now().plusSeconds(300));
    }

    private HttpResponse<String> verify(UUID customer, String body, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(uri("/v1/customers/" + customer + "/pin/verify"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
