package com.paymentplatform.notification_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class SignedWebhookOutboxPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.GET,"/internal/v1/webhook-deliveries/*").hasAuthority("SCOPE_notifications:delivery:read");
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/webhook-deliveries/*/retry").hasAuthority("SCOPE_notifications:delivery:retry");
 }
}
