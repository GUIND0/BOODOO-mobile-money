package com.paymentplatform.customer_service.security;
public interface EndpointPolicy { void apply(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry requests); }
