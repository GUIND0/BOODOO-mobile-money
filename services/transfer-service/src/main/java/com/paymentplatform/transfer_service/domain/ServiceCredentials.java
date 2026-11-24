package com.paymentplatform.transfer_service.domain;
import com.paymentplatform.transfer_service.client.DependencyClient;import org.springframework.boot.context.properties.EnableConfigurationProperties;import org.springframework.security.oauth2.jwt.JwtDecoder;import org.springframework.stereotype.Component;import tools.jackson.databind.ObjectMapper;import java.util.UUID;
@Component @EnableConfigurationProperties(TransferSettings.class) public class ServiceCredentials {
 private final OAuthCredentials oauth;private final TransferSettings settings;private final JwtDecoder decoder;private final ObjectMapper json;
 public ServiceCredentials(TransferSettings settings,JwtDecoder decoder,ObjectMapper json,OAuthCredentials oauth){this.oauth=oauth;this.settings=settings;this.decoder=decoder;this.json=json;}
 public String bearer(String tenant){try{var refreshed=oauth.bearer(tenant);if(refreshed.isPresent())return refreshed.get();String token=DependencyClient.text(json.readTree(settings.serviceTokensJson()),tenant);var jwt=decoder.decode(token);if(!tenant.equals(jwt.getClaimAsString("integrator_id"))||!"service".equals(jwt.getClaims().get("actor_type"))||!"transfer-service".equals(jwt.getSubject()))throw DependencyClient.unavailable();return token;}catch(Exception error){throw DependencyClient.unavailable();}}
 public UUID feeAccount(String tenant){try{return UUID.fromString(DependencyClient.text(json.readTree(settings.feeAccountsJson()),tenant));}catch(Exception error){throw DependencyClient.unavailable();}}
}
