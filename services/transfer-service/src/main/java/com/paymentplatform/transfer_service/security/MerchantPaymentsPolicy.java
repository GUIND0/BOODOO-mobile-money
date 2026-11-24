package com.paymentplatform.transfer_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class MerchantPaymentsPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/v1/merchants").hasAuthority("SCOPE_merchants:write");
  requests.requestMatchers(HttpMethod.GET,"/v1/merchants/*").hasAuthority("SCOPE_merchants:read");
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/merchants/*/state").hasAuthority("SCOPE_merchants:control");
  requests.requestMatchers(HttpMethod.POST,"/v1/merchant-payments").hasAuthority("SCOPE_payments:write");
 }
}
