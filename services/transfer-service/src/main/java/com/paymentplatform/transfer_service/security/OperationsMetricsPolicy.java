package com.paymentplatform.transfer_service.security;
@org.springframework.stereotype.Component public class OperationsMetricsPolicy implements EndpointPolicy {
 public void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests) { requests.requestMatchers(org.springframework.http.HttpMethod.GET,"/actuator/metrics","/actuator/metrics/**").access((authentication, context) -> {
  var auth=authentication.get();boolean allowed=auth.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt && "metrics-collector".equals(jwt.getSubject()) && "service".equals(jwt.getClaims().get("actor_type")) && auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("SCOPE_operations:metrics:read"));
  return new org.springframework.security.authorization.AuthorizationDecision(allowed);
 }); }
}
