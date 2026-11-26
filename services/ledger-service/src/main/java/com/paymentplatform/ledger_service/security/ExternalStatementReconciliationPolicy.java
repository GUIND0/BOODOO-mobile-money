package com.paymentplatform.ledger_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class ExternalStatementReconciliationPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.GET,"/internal/v1/accounts/*/statement").hasAuthority("SCOPE_ledger:statements:read");
 }
}
