package com.paymentplatform.reconciliation_service.security;
import org.springframework.http.HttpMethod;
@org.springframework.stereotype.Component public class ExternalStatementReconciliationPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) {
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/external-statements").hasAuthority("SCOPE_reconciliation:statements:write");
  requests.requestMatchers(HttpMethod.GET,"/internal/v1/external-statements/*","/internal/v1/external-statements/*/investigations").hasAuthority("SCOPE_reconciliation:statements:read");
  requests.requestMatchers(HttpMethod.POST,"/internal/v1/external-statements/*/investigations").hasAuthority("SCOPE_reconciliation:investigate");
 }
}
