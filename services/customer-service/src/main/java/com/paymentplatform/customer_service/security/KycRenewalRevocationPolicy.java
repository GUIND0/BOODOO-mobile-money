package com.paymentplatform.customer_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class KycRenewalRevocationPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/customers/*/kyc-lifecycle").hasAuthority("SCOPE_kyc:lifecycle");
 }
}
