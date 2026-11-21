package com.paymentplatform.customer_service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerServiceApplicationTests {
    private static final String ISSUER = "https://test-issuer.example";
    private static final String AUDIENCE = "boodoo-customer-api";
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final HttpServer KEYS = keyServer();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PROFILE = """
            {"externalReference":"user-001","firstName":"Awa","lastName":"Test","phoneNumber":"+22370000001"}
            """;
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @org.springframework.test.context.bean.override.mockito.MockitoBean java.time.Clock customerClock;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("boodoo.security.issuer", () -> ISSUER);
        registry.add("boodoo.security.audience", () -> AUDIENCE);
        registry.add("boodoo.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
    }

    @BeforeEach
    void clearCustomers() {
        org.mockito.Mockito.when(customerClock.instant()).thenAnswer(inv -> Instant.now());
        org.mockito.Mockito.when(customerClock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
        jdbc.execute("TRUNCATE kyc_decision_requests, kyc_decisions, kyc_submission_requests, kyc_submissions, customer_profile_updates, customer_creation_requests, customers");
    }

    @AfterAll
    static void closeFixtures() { HTTP.close(); KEYS.stop(0); }

    @Test
    void startsWithMigratedPostgresAndReportsHealthy() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(4);
        var response = HTTP.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        assertThat(json.readTree(response.body()).has("components")).isFalse();
    }

    @Test
    void createsPendingCustomerForAuthenticatedIntegrator() throws Exception {
        var response = post(PROFILE, "create-1", token("partner-a"));
        assertThat(response.statusCode()).isEqualTo(201);
        var body = json.readTree(response.body());
        assertThat(UUID.fromString(body.get("id").asString())).isNotNull();
        assertThat(Instant.parse(body.get("createdAt").asString())).isNotNull();
        assertThat(body.get("externalReference").asString()).isEqualTo("user-001");
        assertThat(body.get("firstName").asString()).isEqualTo("Awa");
        assertThat(body.get("phoneNumber").asString()).isEqualTo("+22370000001");
        assertThat(body.get("kycStatus").asString()).isEqualTo("PENDING");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT integrator_id FROM customers", String.class)).isEqualTo("partner-a");
        assertCounts(1);
    }

    @Test
    void replaysCanonicalRequestAndOriginalSnapshot() throws Exception {
        String auth = token("partner-a");
        var original = post(PROFILE, "stable-key", auth);
        String reordered = """
                {"phoneNumber":" +22370000001 ","lastName":"Test","firstName":" Awa ","externalReference":"user-001"}
                """;
        jdbc.update("UPDATE customers SET first_name = 'Updated' WHERE integrator_id = 'partner-a'");
        var replay = post(reordered, "stable-key", auth);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertCounts(1);
    }

    @Test
    void rejectsKeyReuseWithDifferentPayloadAndPreservesOriginal() throws Exception {
        String auth = token("partner-a");
        var original = post(PROFILE, "stable-key", auth);
        var conflict = post(PROFILE.replace("Awa", "Mariam"), "stable-key", auth);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(post(PROFILE, "stable-key", auth).body()).isEqualTo(original.body());
        assertCounts(1);
    }

    @Test
    void preventsDuplicateExternalReferenceAndPhoneWithinIntegrator() throws Exception {
        String auth = token("partner-a");
        assertThat(post(PROFILE, "first", auth).statusCode()).isEqualTo(201);
        for (String duplicate : List.of(PROFILE.replace("+22370000001", "+22370000002"), PROFILE.replace("user-001", "user-002"))) {
            var response = post(duplicate, UUID.randomUUID().toString(), auth);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("CUSTOMER_ALREADY_EXISTS");
        }
        assertCounts(1);
    }

    @Test
    void separatesPartnerNamespacesForReferencesPhonesAndKeys() throws Exception {
        var first = post(PROFILE, "same-key", token("partner-a"));
        var second = post(PROFILE, "same-key", token("partner-b"));
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(json.readTree(first.body()).get("id").asString()).isNotEqualTo(json.readTree(second.body()).get("id").asString());
        assertCounts(2);
        assertThat(post(PROFILE, "same-key", token("partner-a")).body()).isEqualTo(first.body());
        assertThat(post(PROFILE, "same-key", token("partner-b")).body()).isEqualTo(second.body());
    }

    @Test
    void ignoresSpoofedPartnerHeaders() throws Exception {
        var request = request(PROFILE, "spoof", token("partner-a"))
                .header("X-Integrator-Id", "partner-b").header("X-Tenant-Id", "partner-b").build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT integrator_id FROM customers", String.class)).isEqualTo("partner-a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{malformed", "{\"externalReference\":null}"})
    void rejectsIncompleteOrMalformedBody(String profile) throws Exception {
        assertThat(post(profile, "invalid", token("partner-a")).statusCode()).isEqualTo(400);
        assertCounts(0);
    }

    @Test
    void rejectsUntrustedBodyFields() throws Exception {
        for (String extra : List.of("\"kycStatus\":\"APPROVED\"", "\"integratorId\":\"partner-b\"", "\"phoneVerified\":true", "\"pin\":\"123456\"")) {
            String profile = PROFILE.strip().replace("}", "," + extra + "}");
            var response = post(profile, "untrusted", token("partner-a"));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("+22370000001", "123456");
        }
        assertCounts(0);
    }

    @Test
    void validatesProfileLengthsRequiredNamesAndPhoneFormat() throws Exception {
        for (String profile : List.of(PROFILE.replace("Awa", " "), PROFILE.replace("Test", " "),
                PROFILE.replace("user-001", " "), PROFILE.replace("Awa", "a".repeat(101)),
                PROFILE.replace("Test", "b".repeat(101)), PROFILE.replace("user-001", "c".repeat(129)),
                PROFILE.replace("+22370000001", "70000001"), PROFILE.replace("+22370000001", "+1234567890123456"))) {
            assertThat(post(profile, "invalid-profile", token("partner-a")).statusCode()).isEqualTo(400);
        }
        assertCounts(0);
    }

    @Test
    void requiresValidIdempotencyHeader() throws Exception {
        for (String key : List.of("", "bad key", "k".repeat(129), "invalid/key")) {
            assertThat(post(PROFILE, key, token("partner-a")).statusCode()).isEqualTo(400);
        }
        assertThat(post(PROFILE, null, token("partner-a")).statusCode()).isEqualTo(400);
        assertCounts(0);
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        var request = HttpRequest.newBuilder(uri("/v1/customers"))
                .header("Authorization", "Bearer " + token("partner-a"))
                .header("Idempotency-Key", "wrong-type")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(PROFILE)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(415);
        assertCounts(0);
    }

    @Test
    void requiresAuthenticationAndCreationScope() throws Exception {
        assertThat(post(PROFILE, "unauthenticated", null).statusCode()).isEqualTo(401);
        String readOnly = signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:read", Instant.now().plusSeconds(300));
        assertThat(post(PROFILE, "read-only", readOnly).statusCode()).isEqualTo(403);
        assertCounts(0);
    }

    @Test
    void validatesSignatureIssuerAudienceExpiryAndPartnerClaim() throws Exception {
        for (String invalid : List.of(
                signedToken(signingKey(), ISSUER, AUDIENCE, "partner-a", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, "https://wrong-issuer.example", AUDIENCE, "partner-a", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, "another-api", "partner-a", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:write", Instant.now().minusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, null, "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, List.of("partner-a"), "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "invalid/partner", "customers:write", Instant.now().plusSeconds(300)),
                signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:write", null))) {
            assertThat(post(PROFILE, "invalid-token", invalid).statusCode()).isEqualTo(401);
        }
        assertCounts(0);
    }

    @Test
    void deniesOtherRoutes() throws Exception {
        for (String path : List.of("/actuator/info", "/v1/customers", "/v1/customers/unknown")) {
            var response = HTTP.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token("partner-a"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
    }

    @Test
    void concurrentRetriesProduceOneCustomerAndOneSnapshot() throws Exception {
        String auth = token("partner-a");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { start.await(); return post(PROFILE, "concurrent", auth); }));
            start.countDown();
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(20, TimeUnit.SECONDS));
            assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
            assertThat(responses.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
            assertThat(responses.stream().filter(r -> r.headers().firstValue("Idempotency-Replayed").orElse("").equals("false")).count()).isEqualTo(1);
            assertCounts(1);
        }
    }

    @Test
    void concurrentDuplicateProfilesWithDifferentKeysDoNotCreateDuplicates() throws Exception {
        String auth = token("partner-a");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return post(PROFILE, "duplicate-a", auth); });
            var second = executor.submit(() -> { start.await(); return post(PROFILE, "duplicate-b", auth); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(), second.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
            assertCounts(1);
        }
    }

    @Test
    void rollsBackCustomerIfSnapshotFailsAndAllowsRetry() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION reject_test_creation() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.idempotency_key = 'rollback-test' THEN
                        RAISE EXCEPTION 'Injected test failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER reject_test_creation BEFORE INSERT ON customer_creation_requests FOR EACH ROW EXECUTE FUNCTION reject_test_creation()");
        String auth = token("partner-a");
        try {
            var response = post(PROFILE, "rollback-test", auth);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("Injected test failure", "+22370000001");
            assertCounts(0);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_creation ON customer_creation_requests");
            jdbc.execute("DROP FUNCTION reject_test_creation()");
        }
        assertThat(post(PROFILE, "rollback-test", auth).statusCode()).isEqualTo(201);
        assertCounts(1);
    }

    @Test
    void confirmsOwnershipWithoutDisclosingProfileAndKeepsKycPending() throws Exception {
        var created = post(PROFILE, "ownership", token("partner-a"));
        UUID customer = UUID.fromString(json.readTree(created.body()).get("id").asString());
        String allowed = signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-a", "customers:ownership:read", Instant.now().plusSeconds(300));
        var response = ownership(customer, allowed);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(ownership(UUID.randomUUID(), allowed).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers", String.class)).isEqualTo("PENDING");
    }

    @Test
    void ownershipLookupDoesNotRevealAnotherIntegratorsCustomer() throws Exception {
        var created = post(PROFILE, "ownership", token("partner-a"));
        UUID customer = UUID.fromString(json.readTree(created.body()).get("id").asString());
        String foreign = signedToken(SIGNING_KEY, ISSUER, AUDIENCE, "partner-b", "customers:ownership:read", Instant.now().plusSeconds(300));
        assertThat(ownership(customer, foreign).statusCode()).isEqualTo(404);
        assertThat(ownership(customer, foreign).body()).isEmpty();
    }

    @Test
    void ownershipLookupRequiresItsOwnScope() throws Exception {
        assertThat(ownership(UUID.randomUUID(), null).statusCode()).isEqualTo(401);
        assertThat(ownership(UUID.randomUUID(), token("partner-a")).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> ownership(UUID customer, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri("/internal/v1/customers/" + customer + "/ownership")).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readsCurrentProfileWithReadScopeAndNoCache() throws Exception {
        var created = post(PROFILE, "read", token("partner-a"));
        UUID id = UUID.fromString(json.readTree(created.body()).get("id").asString());
        var read = readProfile(id, scopedToken("partner-a", "customers:read"));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.readTree(read.body()).get("id").asString()).isEqualTo(id.toString());
        assertThat(read.headers().firstValue("ETag")).contains("\"0\"");
        assertThat(read.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(read.headers().firstValue("Set-Cookie")).isEmpty();
        jdbc.update("UPDATE customers SET first_name = 'Changed' WHERE id = ?", id);
        assertThat(json.readTree(readProfile(id, scopedToken("partner-a", "customers:read")).body()).get("firstName").asString()).isEqualTo("Changed");
        assertThat(post(PROFILE, "read", token("partner-a")).body()).isEqualTo(created.body());
    }

    @Test
    void profileReadDoesNotRevealForeignOrMissingCustomers() throws Exception {
        UUID id = UUID.fromString(json.readTree(post(PROFILE, "read", token("partner-a")).body()).get("id").asString());
        var foreign = readProfile(id, scopedToken("partner-b", "customers:read"));
        var missing = readProfile(UUID.randomUUID(), scopedToken("partner-b", "customers:read"));
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(json.readTree(foreign.body()).get("code").asString()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(json.readTree(missing.body()).get("code").asString()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(foreign.body()).doesNotContain("Awa", "+22370000001", "user-001");
    }

    @Test
    void profileReadRequiresAuthenticationItsOwnScopeAndAValidUuid() throws Exception {
        assertThat(readProfile(UUID.randomUUID(), null).statusCode()).isEqualTo(401);
        assertThat(readProfile(UUID.randomUUID(), token("partner-a")).statusCode()).isEqualTo(403);
        var request = HttpRequest.newBuilder(uri("/v1/customers/not-a-uuid"))
                .header("Authorization", "Bearer " + scopedToken("partner-a", "customers:read")).GET().build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    private String scopedToken(String partner, String scopes) throws Exception {
        return signedToken(SIGNING_KEY, ISSUER, AUDIENCE, partner, scopes, Instant.now().plusSeconds(300));
    }

    private HttpResponse<String> readProfile(UUID customer, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/v1/customers/" + customer)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final String COMPLETE_PROFILE = """
            {"dateOfBirth":"1990-01-02","nationality":"ml","residenceCountry":"ML","addressLine":"Quartier Test","city":"Bamako"}
            """;

    @Test
    void updatesProfileWithRevisionAndImmutableAuditWithoutChangingCreationSnapshot() throws Exception {
        var original=post(PROFILE,"profile-fixture",token("partner-a"));
        UUID id=UUID.fromString(json.readTree(original.body()).get("id").asString());
        var updated=patchProfile(id,COMPLETE_PROFILE,"\"0\"",token("partner-a"));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.headers().firstValue("ETag")).contains("\"1\"");
        assertThat(json.readTree(updated.body()).get("nationality").asString()).isEqualTo("ML");
        assertThat(json.readTree(updated.body()).get("dateOfBirth").asString()).isEqualTo("1990-01-02");
        assertThat(json.readTree(updated.body()).get("kycStatus").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM customer_profile_updates",String.class)).isEqualTo("integration-client");
        assertThat(patchProfile(id,COMPLETE_PROFILE,"\"1\"",token("partner-a")).headers().firstValue("ETag")).contains("\"1\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_profile_updates",Integer.class)).isEqualTo(1);
        assertThat(post(PROFILE,"profile-fixture",token("partner-a")).body()).isEqualTo(original.body());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM customer_profile_updates")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void patchRequiresCurrentStrongEtagAndRejectsReadOnlyFields() throws Exception {
        UUID id=createProfileFixture();
        assertThat(patchProfile(id,COMPLETE_PROFILE,null,token("partner-a")).statusCode()).isEqualTo(428);
        for(String tag:List.of("0","*","W/\"0\"","\"9999999999999999999\""))
            assertThat(patchProfile(id,COMPLETE_PROFILE,tag,token("partner-a")).statusCode()).isEqualTo(400);
        assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",token("partner-a")).statusCode()).isEqualTo(200);
        assertThat(patchProfile(id,"{\"firstName\":\"Changed\"}","\"0\"",token("partner-a")).statusCode()).isEqualTo(412);
        for(String body:List.of("{}","{\"kycStatus\":\"APPROVED\"}","{\"phoneNumber\":\"+22370000002\"}",
                "{\"externalReference\":\"changed\"}","{\"dateOfBirth\":\"2999-01-01\"}","{\"nationality\":\"ZZ\"}",
                "{\"firstName\":\" \"}","{\"city\":null}")) {
            var response=patchProfile(id,body,"\"1\"",token("partner-a"));
            assertThat(response.statusCode()).as(body).isEqualTo(400);
        }
    }

    @Test
    void patchEnforcesPartnerWritePermissionAndAuditableActor() throws Exception {
        UUID id=createProfileFixture();
        assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",null).statusCode()).isEqualTo(401);
        assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",scopedToken("partner-a","customers:read")).statusCode()).isEqualTo(403);
        assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",token("partner-b")).statusCode()).isEqualTo(404);
        assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",actorToken("partner-a","customers:write",null,null)).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_profile_updates",Integer.class)).isZero();
    }

    @Test
    void concurrentProfilePatchesCannotLoseAnUpdate() throws Exception {
        UUID id=createProfileFixture();String auth=token("partner-a");CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{start.await();return patchProfile(id,"{\"firstName\":\"One\"}","\"0\"",auth).statusCode();});
            var b=pool.submit(()->{start.await();return patchProfile(id,"{\"firstName\":\"Two\"}","\"0\"",auth).statusCode();});
            start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        }
        assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_profile_updates",Integer.class)).isEqualTo(1);
    }

    @Test
    void failedProfileAuditRollsBackTheProfileUpdate() throws Exception {
        UUID id=createProfileFixture();
        jdbc.execute("CREATE FUNCTION reject_profile_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_profile_test BEFORE INSERT ON customer_profile_updates FOR EACH ROW EXECUTE FUNCTION reject_profile_test()");
        try {
            assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",token("partner-a")).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT date_of_birth IS NULL FROM customers",Boolean.class)).isTrue();
        }finally{jdbc.execute("DROP TRIGGER reject_profile_test ON customer_profile_updates");jdbc.execute("DROP FUNCTION reject_profile_test()");}
    }

    private UUID createProfileFixture() throws Exception {
        return UUID.fromString(json.readTree(post(PROFILE,"profile-fixture",token("partner-a")).body()).get("id").asString());
    }
    private HttpResponse<String> patchProfile(UUID id,String body,String tag,String auth)throws Exception {
        var request=HttpRequest.newBuilder(uri("/v1/customers/"+id)).header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body));
        if(tag!=null)request.header("If-Match",tag);if(auth!=null)request.header("Authorization","Bearer "+auth);
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String actorToken(String partner,String scopes,String subject,String actorType)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).claim("integrator_id",partner).claim("scope",scopes)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if(subject!=null)claims.subject(subject);if(actorType!=null)claims.claim("actor_type",actorType);
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),claims.build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));return jwt.serialize();
    }

    private static final String DOCUMENTS="{\"documents\":[{\"type\":\"NATIONAL_ID\",\"reference\":\"vault-document-1\",\"issuingCountry\":\"ML\",\"expiresOn\":\"2999-12-31\"}]}";

    @Test
    void submitsAndReadsImmutableKycSnapshotWithoutApprovingIt() throws Exception {
        UUID id=completeProfileFixture();String auth=scopedToken("partner-a","kyc:submit kyc:read");
        var response=submitKyc(id,DOCUMENTS,"kyc-one","\"1\"",auth);assertThat(response.statusCode()).isEqualTo(201);
        UUID submission=UUID.fromString(json.readTree(response.body()).get("id").asString());
        assertThat(json.readTree(response.body()).get("profile").get("dateOfBirth").asString()).isEqualTo("1990-01-02");
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("UNDER_REVIEW");
        assertThat(readKyc(id,submission,auth).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers",String.class)).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(2);
        assertThat(submitKyc(id,DOCUMENTS,"kyc-one","\"1\"",auth).body()).isEqualTo(response.body());
        assertThat(patchProfile(id,"{\"city\":\"Changed\"}","\"2\"",token("partner-a")).statusCode()).isEqualTo(409);
        assertThatThrownBy(()->jdbc.update("UPDATE kyc_submissions SET submitted_by='changed'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void kycRequiresCompleteProfileCurrentRevisionAndDedicatedPermissions() throws Exception {
        UUID id=createProfileFixture();String auth=scopedToken("partner-a","kyc:submit");
        assertThat(submitKyc(id,DOCUMENTS,"incomplete","\"0\"",auth).statusCode()).isEqualTo(409);
        assertThat(submitKyc(id,DOCUMENTS,"missing",null,auth).statusCode()).isEqualTo(428);
        assertThat(submitKyc(id,DOCUMENTS,"scope","\"0\"",token("partner-a")).statusCode()).isEqualTo(403);
        assertThat(submitKyc(id,DOCUMENTS,"foreign","\"0\"",scopedToken("partner-b","kyc:submit")).statusCode()).isEqualTo(404);
        assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",token("partner-a")).statusCode()).isEqualTo(200);
        assertThat(submitKyc(id,DOCUMENTS,"stale","\"0\"",auth).statusCode()).isEqualTo(412);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_submissions",Integer.class)).isZero();
    }

    @Test
    void kycRejectsInvalidEvidenceAndConflictingIdempotencyReuse() throws Exception {
        UUID id=completeProfileFixture();String auth=scopedToken("partner-a","kyc:submit");
        for(String body:List.of("{}","{\"documents\":[]}",DOCUMENTS.replace("vault-document-1","https://example.com/document"),
                DOCUMENTS.replace("NATIONAL_ID","UNKNOWN"),DOCUMENTS.replace("\"ML\"","\"ZZ\""),DOCUMENTS.replace("2999-12-31","2000-01-01")))
            assertThat(submitKyc(id,body,"invalid","\"1\"",auth).statusCode()).as(body).isEqualTo(400);
        assertThat(submitKyc(id,DOCUMENTS,"stable","\"1\"",auth).statusCode()).isEqualTo(201);
        assertThat(submitKyc(id,DOCUMENTS.replace("vault-document-1","different"),"stable","\"1\"",auth).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_submissions",Integer.class)).isEqualTo(1);
    }

    @Test
    void kycReplaySurvivesDocumentExpiryAndDoesNotResubmitTheProfile() throws Exception {
        UUID id=completeProfileFixture();String auth=scopedToken("partner-a","kyc:submit");
        var first=submitKyc(id,DOCUMENTS,"stable","\"1\"",auth);assertThat(first.statusCode()).isEqualTo(201);
        org.mockito.Mockito.when(customerClock.instant()).thenReturn(Instant.parse("3000-01-02T00:00:00Z"));
        var replay=submitKyc(id,DOCUMENTS,"stable","\"1\"",auth);
        assertThat(replay.statusCode()).isEqualTo(201);assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(2);
    }

    @Test
    void kycReadsDoNotLeakOtherPartnerSubmissionsOrGrantImplicitReadPermission() throws Exception {
        UUID id=completeProfileFixture();var created=submitKyc(id,DOCUMENTS,"kyc","\"1\"",scopedToken("partner-a","kyc:submit"));
        UUID submission=UUID.fromString(json.readTree(created.body()).get("id").asString());
        assertThat(readKyc(id,submission,scopedToken("partner-b","kyc:read")).statusCode()).isEqualTo(404);
        assertThat(readKyc(UUID.randomUUID(),submission,scopedToken("partner-a","kyc:read")).statusCode()).isEqualTo(404);
        assertThat(readKyc(id,submission,scopedToken("partner-a","kyc:submit")).statusCode()).isEqualTo(403);
        assertThat(readKyc(id,submission,null).statusCode()).isEqualTo(401);
    }

    @Test
    void concurrentKycSubmissionsProduceOnlyOneActiveDossier() throws Exception {
        UUID id=completeProfileFixture();String auth=scopedToken("partner-a","kyc:submit");CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();return submitKyc(id,DOCUMENTS,"one","\"1\"",auth).statusCode();});
            var b=pool.submit(()->{start.await();return submitKyc(id,DOCUMENTS,"two","\"1\"",auth).statusCode();});
            start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,412);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_submissions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_submission_requests",Integer.class)).isEqualTo(1);
    }

    @Test
    void failedKycRequestSnapshotRollsBackDossierAndCustomerState() throws Exception {
        UUID id=completeProfileFixture();
        jdbc.execute("CREATE FUNCTION reject_kyc_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_kyc_test BEFORE INSERT ON kyc_submission_requests FOR EACH ROW EXECUTE FUNCTION reject_kyc_test()");
        try{
            assertThat(submitKyc(id,DOCUMENTS,"rollback","\"1\"",scopedToken("partner-a","kyc:submit")).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers",String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_submissions",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(1);
        }finally{jdbc.execute("DROP TRIGGER reject_kyc_test ON kyc_submission_requests");jdbc.execute("DROP FUNCTION reject_kyc_test()");}
    }

    private UUID completeProfileFixture()throws Exception {
        UUID id=createProfileFixture();assertThat(patchProfile(id,COMPLETE_PROFILE,"\"0\"",token("partner-a")).statusCode()).isEqualTo(200);return id;
    }
    private HttpResponse<String> submitKyc(UUID id,String body,String key,String tag,String auth)throws Exception {
        var request=HttpRequest.newBuilder(uri("/v1/customers/"+id+"/kyc-submissions")).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(key!=null)request.header("Idempotency-Key",key);if(tag!=null)request.header("If-Match",tag);
        if(auth!=null)request.header("Authorization","Bearer "+auth);return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> readKyc(UUID id,UUID submission,String auth)throws Exception {
        var request=HttpRequest.newBuilder(uri("/v1/customers/"+id+"/kyc-submissions/"+submission)).GET();
        if(auth!=null)request.header("Authorization","Bearer "+auth);return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private static final String APPROVAL="{\"decision\":\"APPROVED\",\"reasonCode\":\"MANUAL_REVIEW_PASSED\"}";
    private static final String REJECTION="{\"decision\":\"REJECTED\",\"reasonCode\":\"DOCUMENT_UNREADABLE\"}";

    @Test
    void authorizedIndependentOperatorApprovesFrozenKycWithImmutableDecision() throws Exception {
        var fixture=submittedFixture();UUID id=fixture.get(0),submission=fixture.get(1);
        String reviewer=operatorToken("partner-a","operator-1");
        var result=decideKyc(id,submission,APPROVAL,"decision",reviewer);assertThat(result.statusCode()).isEqualTo(201);
        assertThat(json.readTree(result.body()).get("reviewedBy").asString()).isEqualTo("operator-1");
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers",String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(3);
        var dossier=json.readTree(readKyc(id,submission,scopedToken("partner-a","kyc:read")).body());
        assertThat(dossier.get("status").asString()).isEqualTo("APPROVED");
        assertThat(dossier.get("decision").get("reasonCode").asString()).isEqualTo("MANUAL_REVIEW_PASSED");
        assertThat(patchProfile(id,"{\"city\":\"Changed\"}","\"3\"",token("partner-a")).statusCode()).isEqualTo(409);
        assertThatThrownBy(()->jdbc.update("DELETE FROM kyc_decisions")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM kyc_decision_requests")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void decisionReplaysAreStableButPayloadActorAndSecondDecisionConflictsAreRejected() throws Exception {
        var fixture=submittedFixture();UUID id=fixture.get(0),submission=fixture.get(1);
        String reviewer=operatorToken("partner-a","operator-1");var original=decideKyc(id,submission,APPROVAL,"stable",reviewer);
        var replay=decideKyc(id,submission,APPROVAL,"stable",reviewer);
        assertThat(replay.statusCode()).isEqualTo(201);assertThat(replay.body()).isEqualTo(original.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(decideKyc(id,submission,REJECTION,"stable",reviewer).statusCode()).isEqualTo(409);
        assertThat(decideKyc(id,submission,APPROVAL,"stable",operatorToken("partner-a","operator-2")).statusCode()).isEqualTo(409);
        assertThat(decideKyc(id,submission,REJECTION,"new-key",reviewer).statusCode()).isEqualTo(409);
        org.mockito.Mockito.when(customerClock.instant()).thenReturn(Instant.parse("3000-01-02T00:00:00Z"));
        assertThat(decideKyc(id,submission,APPROVAL,"stable",reviewer).body()).isEqualTo(original.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_decisions",Integer.class)).isEqualTo(1);
    }

    @Test
    void reviewRequiresOperatorClaimScopeActorSeparationAndPartnerOwnership() throws Exception {
        var fixture=submittedFixture();UUID id=fixture.get(0),submission=fixture.get(1);
        assertThat(decideKyc(id,submission,APPROVAL,"no-auth",null).statusCode()).isEqualTo(401);
        assertThat(decideKyc(id,submission,APPROVAL,"wrong-scope",scopedToken("partner-a","kyc:submit")).statusCode()).isEqualTo(403);
        assertThat(decideKyc(id,submission,APPROVAL,"no-role",actorToken("partner-a","kyc:review","operator-1",null)).statusCode()).isEqualTo(403);
        assertThat(decideKyc(id,submission,APPROVAL,"missing-actor",actorToken("partner-a","kyc:review",null,"operator")).statusCode()).isEqualTo(403);
        var self=decideKyc(id,submission,APPROVAL,"self",operatorToken("partner-a","integration-client"));
        assertThat(self.statusCode()).isEqualTo(403);assertThat(json.readTree(self.body()).get("code").asString()).isEqualTo("SELF_REVIEW_FORBIDDEN");
        assertThat(decideKyc(id,submission,APPROVAL,"foreign",operatorToken("partner-b","operator-1")).statusCode()).isEqualTo(404);
        assertThat(decideKyc(id,UUID.randomUUID(),APPROVAL,"missing",operatorToken("partner-a","operator-1")).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_decisions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers",String.class)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void reviewRejectsInvalidDecisionReasonAndSpoofedActorFields() throws Exception {
        var fixture=submittedFixture();String reviewer=operatorToken("partner-a","operator-1");
        for(String body:List.of("{}","{\"decision\":\"PENDING\",\"reasonCode\":\"TEST\"}",
                "{\"decision\":\"APPROVED\"}","{\"decision\":\"APPROVED\",\"reasonCode\":\"contains spaces\"}",
                "{\"decision\":\"APPROVED\",\"reasonCode\":\"TEST\",\"reviewedBy\":\"another-actor\"}"))
            assertThat(decideKyc(fixture.get(0),fixture.get(1),body,"invalid",reviewer).statusCode()).isEqualTo(400);
        assertThat(decideKyc(fixture.get(0),fixture.get(1),APPROVAL,null,reviewer).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_decisions",Integer.class)).isZero();
    }

    @Test
    void expiredEvidenceCanBeRejectedButCannotBeApproved() throws Exception {
        var fixture=submittedFixture();String reviewer=operatorToken("partner-a","operator-1");
        org.mockito.Mockito.when(customerClock.instant()).thenReturn(Instant.parse("3000-01-02T00:00:00Z"));
        var denied=decideKyc(fixture.get(0),fixture.get(1),APPROVAL,"expired",reviewer);
        assertThat(denied.statusCode()).isEqualTo(409);assertThat(json.readTree(denied.body()).get("code").asString()).isEqualTo("KYC_DOCUMENT_EXPIRED");
        assertThat(decideKyc(fixture.get(0),fixture.get(1),REJECTION,"reject-expired",reviewer).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers",String.class)).isEqualTo("REJECTED");
    }

    @Test
    void rejectsDecisionWhenFrozenProfileRevisionIsNoLongerCurrent() throws Exception {
        var fixture=submittedFixture();jdbc.update("UPDATE customers SET profile_version=profile_version+1");
        var result=decideKyc(fixture.get(0),fixture.get(1),APPROVAL,"stale",operatorToken("partner-a","operator-1"));
        assertThat(result.statusCode()).isEqualTo(409);assertThat(json.readTree(result.body()).get("code").asString()).isEqualTo("STALE_KYC_SUBMISSION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_decisions",Integer.class)).isZero();
    }

    @Test
    void concurrentOperatorsCanCreateOnlyOneFinalDecision() throws Exception {
        var fixture=submittedFixture();CountDownLatch start=new CountDownLatch(1);
        String first=operatorToken("partner-a","operator-1"),second=operatorToken("partner-a","operator-2");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();return decideKyc(fixture.get(0),fixture.get(1),APPROVAL,"one",first).statusCode();});
            var b=pool.submit(()->{start.await();return decideKyc(fixture.get(0),fixture.get(1),REJECTION,"two",second).statusCode();});
            start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_decisions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(3);
    }

    @Test
    void concurrentDecisionRetriesReturnOneImmutableResult() throws Exception {
        var fixture=submittedFixture();String reviewer=operatorToken("partner-a","operator-1");CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{start.await();return decideKyc(fixture.get(0),fixture.get(1),APPROVAL,"same",reviewer);});
            var b=pool.submit(()->{start.await();return decideKyc(fixture.get(0),fixture.get(1),APPROVAL,"same",reviewer);});
            start.countDown();var x=a.get(20,TimeUnit.SECONDS);var y=b.get(20,TimeUnit.SECONDS);
            assertThat(x.statusCode()).isEqualTo(201);assertThat(y.statusCode()).isEqualTo(201);assertThat(x.body()).isEqualTo(y.body());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_decisions",Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectedProfileCanBeCorrectedAndResubmittedWithoutAnOldReplayOverwritingIt() throws Exception {
        var fixture=submittedFixture();UUID id=fixture.get(0),old=fixture.get(1);String reviewer=operatorToken("partner-a","operator-1");
        var decision=decideKyc(id,old,REJECTION,"reject",reviewer);assertThat(decision.statusCode()).isEqualTo(201);
        assertThat(patchProfile(id,"{\"city\":\"Corrected City\"}","\"3\"",token("partner-a")).statusCode()).isEqualTo(200);
        var next=submitKyc(id,DOCUMENTS.replace("vault-document-1","vault-document-2"),"resubmit","\"4\"",scopedToken("partner-a","kyc:submit"));
        assertThat(next.statusCode()).isEqualTo(201);
        assertThat(decideKyc(id,old,REJECTION,"reject",reviewer).body()).isEqualTo(decision.body());
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers",String.class)).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(5);
        var history=json.readTree(readKyc(id,old,scopedToken("partner-a","kyc:read")).body());
        assertThat(history.get("status").asString()).isEqualTo("REJECTED");
        assertThat(history.get("profile").get("city").asString()).isEqualTo("Bamako");
    }

    @Test
    void failedDecisionRequestPersistenceRollsBackDecisionAndCustomerStatus() throws Exception {
        var fixture=submittedFixture();
        jdbc.execute("CREATE FUNCTION reject_decision_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_decision_test BEFORE INSERT ON kyc_decision_requests FOR EACH ROW EXECUTE FUNCTION reject_decision_test()");
        try{
            assertThat(decideKyc(fixture.get(0),fixture.get(1),APPROVAL,"rollback",operatorToken("partner-a","operator-1")).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_decisions",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT kyc_status FROM customers",String.class)).isEqualTo("UNDER_REVIEW");
            assertThat(jdbc.queryForObject("SELECT profile_version FROM customers",Long.class)).isEqualTo(2);
        }finally{jdbc.execute("DROP TRIGGER reject_decision_test ON kyc_decision_requests");jdbc.execute("DROP FUNCTION reject_decision_test()");}
    }

    @Test
    void upgradesMinimalLegacyCustomersWithoutChangingCreationReplaySnapshots() {
        String schema="customer_upgrade_"+UUID.randomUUID().toString().replace("-","");UUID id=UUID.randomUUID();
        String snapshot="{\"id\":\""+id+"\",\"externalReference\":\"legacy\",\"firstName\":\"Awa\",\"lastName\":\"Test\",\"phoneNumber\":\"+22370000008\",\"kycStatus\":\"PENDING\",\"createdAt\":\"2026-01-01T00:00:00Z\"}";
        try{
            org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("1").load().migrate();
            jdbc.update("INSERT INTO "+schema+".customers (id,integrator_id,external_reference,first_name,last_name,phone_number) VALUES (?,?,?,?,?,?)",id,"legacy-partner","legacy","Awa","Test","+22370000008");
            jdbc.update("INSERT INTO "+schema+".customer_creation_requests (integrator_id,idempotency_key,request_fingerprint,customer_id,creation_response) VALUES (?,?,?,?,?::jsonb)","legacy-partner","legacy-key","0".repeat(64),id,snapshot);
            org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT profile_version FROM "+schema+".customers",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT kyc_status FROM "+schema+".customers",String.class)).isEqualTo("PENDING");
            assertThat(json.readTree(jdbc.queryForObject("SELECT creation_response::text FROM "+schema+".customer_creation_requests",String.class))).isEqualTo(json.readTree(snapshot));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+schema+".kyc_decisions",Integer.class)).isZero();
        }finally{jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }

    private List<UUID> submittedFixture()throws Exception {
        UUID id=completeProfileFixture();var submitted=submitKyc(id,DOCUMENTS,"review-fixture","\"1\"",scopedToken("partner-a","kyc:submit"));
        assertThat(submitted.statusCode()).isEqualTo(201);return List.of(id,UUID.fromString(json.readTree(submitted.body()).get("id").asString()));
    }
    private String operatorToken(String partner,String actor)throws Exception {return actorToken(partner,"kyc:review",actor,"operator");}
    private HttpResponse<String> decideKyc(UUID id,UUID submission,String body,String key,String auth)throws Exception {
        var request=HttpRequest.newBuilder(uri("/v1/customers/"+id+"/kyc-submissions/"+submission+"/decisions"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(key!=null)request.header("Idempotency-Key",key);if(auth!=null)request.header("Authorization","Bearer "+auth);
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private void assertCounts(int expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_creation_requests", Integer.class)).isEqualTo(expected);
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private HttpRequest.Builder request(String body, String key, String token) {
        var builder = HttpRequest.newBuilder(uri("/v1/customers")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }
    private HttpResponse<String> post(String body, String key, String token) throws Exception {
        return HTTP.send(request(body, key, token).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token(String integrator) throws Exception {
        return signedToken(SIGNING_KEY, ISSUER, AUDIENCE, integrator, "customers:write", Instant.now().plusSeconds(300));
    }
    private static String signedToken(RSAKey key, String issuer, String audience, Object integrator, String scope, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("integration-client")
                .claim("scope", scope).issueTime(new Date());
        if (integrator != null) claims.claim("integrator_id", integrator);
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
    private static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("test-key").generate(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static HttpServer keyServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                try (var output = exchange.getResponseBody()) { output.write(keys); }
            });
            server.start(); return server;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
