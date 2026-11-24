package com.paymentplatform.limits_service.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("boodoo.security")
public record ServiceSecurityProperties(@NotBlank @Pattern(regexp = "https?://[^\\s]+") String issuer,
                                         @NotBlank @Pattern(regexp = "https?://[^\\s]+") String jwkSetUri,
                                         @NotBlank String audience) {
}
