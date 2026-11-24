package com.paymentplatform.ledger_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class JournalCancellationBarrierPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/journal-references/*/cancel").hasAuthority("SCOPE_ledger:journals:cancel");
 }
}
