package com.paymentplatform.transfer_service.domain;
import com.paymentplatform.transfer_service.client.DependencyClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;import java.util.concurrent.TimeUnit;
@Component public class OAuthCredentials implements AutoCloseable {
 private final JsonNode clients;private final ObjectMapper json;private final JwtDecoder decoder;
 private final Map<String,String> cache=new HashMap<>();
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public OAuthCredentials(@Value("${boodoo.oauth.clients-json:{}}") String configuration,ObjectMapper json,JwtDecoder decoder){this.json=json;this.decoder=decoder;this.clients=json.readTree(configuration);if(!clients.isObject())throw new IllegalArgumentException("OAuth configuration must be an object");}
 public synchronized Optional<String> bearer(String tenant){
  JsonNode config=clients.get(tenant);if(config==null)return Optional.empty();
  try{
   String scopes=DependencyClient.text(config,"scope");var audiences=config.get("audiences");if(scopes.isBlank()||scopes.length()>2048||audiences==null||!audiences.isArray()||audiences.isEmpty())throw DependencyClient.unavailable();
   String saved=cache.get(tenant);if(saved!=null){try{validate(saved,tenant,scopes,audiences);return Optional.of(saved);}catch(RuntimeException expired){cache.remove(tenant);}}
   URI endpoint=URI.create(DependencyClient.text(config,"tokenEndpoint"));
   boolean loopback=Set.of("127.0.0.1","localhost","[::1]").contains(endpoint.getHost());
   if(!(endpoint.getScheme().equals("https")||(endpoint.getScheme().equals("http")&&loopback))||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null)throw DependencyClient.unavailable();
   String credentials=encode(DependencyClient.text(config,"clientId"))+":"+encode(DependencyClient.text(config,"clientSecret"));
   var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4)).header("Authorization","Basic "+Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/x-www-form-urlencoded").header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope="+encode(scopes))).build();
   var pending=http.sendAsync(request,info->new DependencyClient.LimitedBody());HttpResponse<byte[]> response;
   try{response=pending.get(5,TimeUnit.SECONDS);}catch(Exception error){pending.cancel(true);if(error instanceof InterruptedException)Thread.currentThread().interrupt();throw DependencyClient.unavailable();}
   if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))throw DependencyClient.unavailable();
   var body=json.readTree(response.body());if(!"Bearer".equalsIgnoreCase(DependencyClient.text(body,"token_type")))throw DependencyClient.unavailable();
   String token=DependencyClient.text(body,"access_token");validate(token,tenant,scopes,audiences);cache.put(tenant,token);return Optional.of(token);
  }catch(Exception error){throw DependencyClient.unavailable();}
 }
 private void validate(String token,String tenant,String scopes,JsonNode audiences){
  Jwt jwt=decoder.decode(token);if(!tenant.equals(jwt.getClaimAsString("integrator_id"))||!"transfer-service".equals(jwt.getSubject())||!"service".equals(jwt.getClaims().get("actor_type"))||jwt.getExpiresAt()==null||!jwt.getExpiresAt().isAfter(Instant.now().plusSeconds(30)))throw DependencyClient.unavailable();
  String granted=jwt.getClaimAsString("scope");Set<String> actual=granted==null?Set.of():new HashSet<>(Arrays.asList(granted.split(" ")));
  if(!actual.containsAll(Arrays.asList(scopes.split(" "))))throw DependencyClient.unavailable();
  for(var audience:audiences)if(!audience.isString()||!jwt.getAudience().contains(audience.asString()))throw DependencyClient.unavailable();
 }
 private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 @jakarta.annotation.PreDestroy public void close(){http.close();}
 @Override public String toString(){return "OAuthCredentials[REDACTED]";}
}
