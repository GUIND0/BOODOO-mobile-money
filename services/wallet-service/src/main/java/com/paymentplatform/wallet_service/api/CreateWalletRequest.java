package com.paymentplatform.wallet_service.api;

import jakarta.validation.constraints.*;
import java.util.Locale;
import java.util.UUID;

public record CreateWalletRequest(@NotNull UUID customerId, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    public CreateWalletRequest { currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT); }
}
