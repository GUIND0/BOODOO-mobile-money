package com.paymentplatform.transfer_service;
import com.paymentplatform.transfer_service.domain.OAuthCredentials;
import com.paymentplatform.transfer_service.api.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.ObjectMapper;
import java.net.*;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OAuthCredentialsTests {
 private final ObjectMapper json=new ObjectMapper();private HttpServer server;private final AtomicInteger calls=new AtomicInteger();
 @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/token",x->{calls.incrementAndGet();byte[] bytes="{\"access_token\":\"fresh-token\",\"token_type\":\"Bearer\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}});server.start();}
 @AfterEach void close(){server.stop(0);}
 private String config(String endpoint){return json.writeValueAsString(Map.of("partner-a",Map.of("tokenEndpoint",endpoint,"clientId","transfer","clientSecret","test-secret","scope","ledger:post:transfer","audiences",List.of("boodoo-transfer-api","boodoo-ledger-api"))));}
 private Jwt jwt(String tenant,Instant expiry){return Jwt.withTokenValue("fresh-token").header("alg","RS256").issuer("https://issuer.example").subject("transfer-service").audience(List.of("boodoo-transfer-api","boodoo-ledger-api")).claim("scope","ledger:post:transfer").claim("actor_type","service").claim("integrator_id",tenant).expiresAt(expiry).build();}
 @Test void cachesOnlyValidatedTenantBoundTokens(){var decoder=mock(JwtDecoder.class);when(decoder.decode("fresh-token")).thenReturn(jwt("partner-a",Instant.now().plusSeconds(300)));try(var adapter=new OAuthCredentials(config("http://127.0.0.1:"+server.getAddress().getPort()+"/token"),json,decoder)){assertThat(adapter.bearer("partner-a")).contains("fresh-token");assertThat(adapter.bearer("partner-a")).contains("fresh-token");assertThat(adapter.bearer("partner-b")).isEmpty();assertThat(calls.get()).isEqualTo(1);assertThat(adapter.toString()).doesNotContain("test-secret");}}
 @Test void rejectsForeignAndNearExpiryTokens(){var decoder=mock(JwtDecoder.class);when(decoder.decode("fresh-token")).thenReturn(jwt("partner-b",Instant.now().plusSeconds(300)),jwt("partner-a",Instant.now().plusSeconds(10)));try(var adapter=new OAuthCredentials(config("http://127.0.0.1:"+server.getAddress().getPort()+"/token"),json,decoder)){assertThatThrownBy(()->adapter.bearer("partner-a")).isInstanceOf(ApiException.class);assertThatThrownBy(()->adapter.bearer("partner-a")).isInstanceOf(ApiException.class);assertThat(calls.get()).isEqualTo(2);}}
 @Test void refusesPlaintextRemoteTokenEndpoints(){try(var adapter=new OAuthCredentials(config("http://issuer.example/token"),json,mock(JwtDecoder.class))){assertThatThrownBy(()->adapter.bearer("partner-a")).isInstanceOf(ApiException.class);assertThat(calls.get()).isZero();}}
}
