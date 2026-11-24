package com.paymentplatform.limits_service.domain;
import jakarta.validation.constraints.*;import org.springframework.validation.annotation.Validated;import org.springframework.boot.context.properties.ConfigurationProperties;
@Validated @ConfigurationProperties("boodoo.limits") public record LimitPolicy(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,64}") String version,@NotNull @Positive Long perTransaction,@NotNull @Positive Long daily,@NotNull @Positive Long monthly,@NotNull @Min(10) @Max(3600) Integer ttlSeconds){}
