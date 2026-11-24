package com.paymentplatform.transfer_service.domain;
import org.springframework.boot.context.properties.ConfigurationProperties;import jakarta.validation.constraints.NotBlank;import org.springframework.validation.annotation.Validated;
@Validated @ConfigurationProperties("boodoo.transfers") public record TransferSettings(@NotBlank String serviceTokensJson,@NotBlank String feeAccountsJson){@Override public String toString(){return "TransferSettings[REDACTED]";}}
