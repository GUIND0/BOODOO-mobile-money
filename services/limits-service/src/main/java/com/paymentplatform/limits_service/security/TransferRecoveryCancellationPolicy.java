package com.paymentplatform.limits_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class TransferRecoveryCancellationPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/reservation-references/*/cancel").hasAuthority("SCOPE_limits:reservations:settle");
 }
}
