package com.paymentplatform.ledger_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class LinkedJournalReversalsPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/journals/*/reversal").hasAuthority("SCOPE_ledger:reversals:request");
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/reversals/*/approval").hasAuthority("SCOPE_ledger:reversals:approve");
  requests.requestMatchers(HttpMethod.GET,"/internal/v1/reversals/*").hasAuthority("SCOPE_ledger:reversals:read");
 }
}
