package com.paymentplatform.customer_service.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerSecurityProperties.class)
public class SecurityConfiguration {
    private static final Pattern INTEGRATOR_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    @Bean
    JwtDecoder jwtDecoder(CustomerSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> partnerClaims = jwt -> {
            Object integrator = jwt.getClaims().get("integrator_id");
            boolean valid = integrator instanceof String id && INTEGRATOR_ID.matcher(id).matches()
                    && jwt.getExpiresAt() != null && jwt.getAudience() != null
                    && jwt.getAudience().contains(properties.audience());
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required partner claims are missing or invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), partnerClaims));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/customers").hasAuthority("SCOPE_customers:read")
                        .requestMatchers(HttpMethod.POST, "/v1/customers").hasAuthority("SCOPE_customers:write")
                        .requestMatchers(HttpMethod.GET, "/v1/customers/*").hasAuthority("SCOPE_customers:read")
                        .requestMatchers(HttpMethod.GET, "/v1/customers/*/profile-history").hasAuthority("SCOPE_customers:audit:read")
                        .requestMatchers(HttpMethod.PATCH, "/v1/customers/*").hasAuthority("SCOPE_customers:write")
                        .requestMatchers(HttpMethod.POST, "/v1/customers/*/kyc-submissions").hasAuthority("SCOPE_kyc:submit")
                        .requestMatchers(HttpMethod.GET, "/v1/customers/*/kyc-submissions", "/v1/customers/*/kyc-submissions/*").hasAuthority("SCOPE_kyc:read")
                        .requestMatchers(HttpMethod.POST, "/v1/customers/*/kyc-submissions/*/decisions").hasAuthority("SCOPE_kyc:review")
                        .requestMatchers(HttpMethod.GET, "/v1/kyc-review-queue").hasAuthority("SCOPE_kyc:review")
                        .requestMatchers(HttpMethod.GET, "/internal/v1/customers/*/kyc-approval").hasAuthority("SCOPE_customers:kyc:read")
                        .requestMatchers(HttpMethod.GET, "/internal/v1/customers/*/ownership")
                        .hasAuthority("SCOPE_customers:ownership:read")
                        .requestMatchers(HttpMethod.POST,"/internal/v1/customers/*/kyc-lifecycle").hasAuthority("SCOPE_kyc:lifecycle")
                        .anyRequest().denyAll())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .build();
    }
}
