package com.paymentplatform.wallet_service.activation;
import com.paymentplatform.wallet_service.client.DependencyClient;import org.springframework.beans.factory.annotation.Value;import org.springframework.security.oauth2.jwt.JwtDecoder;import org.springframework.stereotype.Component;import tools.jackson.databind.ObjectMapper;import java.time.Instant;
@Component public class ControlCredentials {
 private final JwtDecoder decoder;private final ObjectMapper json;private final String tokens;
 public ControlCredentials(JwtDecoder decoder,ObjectMapper json,@Value("${boodoo.controls.service-tokens-json:{}}") String tokens){this.decoder=decoder;this.json=json;this.tokens=tokens;}
 public String bearer(String tenant){try{String token=DependencyClient.text(json.readTree(tokens),tenant);var jwt=decoder.decode(token);if(!tenant.equals(jwt.getClaimAsString("integrator_id"))||!"service".equals(jwt.getClaims().get("actor_type"))||!"wallet-service".equals(jwt.getSubject())||jwt.getExpiresAt()==null||!jwt.getExpiresAt().isAfter(Instant.now().plusSeconds(30)))throw DependencyClient.unavailable();return token;}catch(Exception error){throw DependencyClient.unavailable();}}
 @Override public String toString(){return "ControlCredentials[REDACTED]";}
}
