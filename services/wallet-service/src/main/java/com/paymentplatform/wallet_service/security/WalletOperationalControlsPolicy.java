package com.paymentplatform.wallet_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class WalletOperationalControlsPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/wallets/*/control").hasAuthority("SCOPE_wallets:control");
 }
}
