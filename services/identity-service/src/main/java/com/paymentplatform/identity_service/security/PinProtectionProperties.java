package com.paymentplatform.identity_service.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("boodoo.pin")
public record PinProtectionProperties(@NotBlank String pepper,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String pepperVersion) {
    @Override
    public String toString() { return "PinProtectionProperties[REDACTED]"; }
}
