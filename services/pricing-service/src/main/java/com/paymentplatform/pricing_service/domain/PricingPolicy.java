package com.paymentplatform.pricing_service.domain;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@Validated @ConfigurationProperties("boodoo.pricing")
public record PricingPolicy(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,64}") String version,@NotNull @Min(0) @Max(10000) Integer basisPoints,@NotNull @Min(0) Long flatFee,@NotNull @Min(10) @Max(3600) Integer ttlSeconds){}
