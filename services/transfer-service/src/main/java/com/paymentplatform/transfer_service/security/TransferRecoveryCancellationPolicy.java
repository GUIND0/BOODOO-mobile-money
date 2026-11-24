package com.paymentplatform.transfer_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class TransferRecoveryCancellationPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/v1/transfers/*/cancellation").hasAuthority("SCOPE_transfers:cancel");
 }
}
