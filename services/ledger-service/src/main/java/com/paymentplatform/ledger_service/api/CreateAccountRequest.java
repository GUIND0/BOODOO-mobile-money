package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.account.AccountType;
import jakarta.validation.constraints.*;
import java.util.Locale;
public record CreateAccountRequest(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_-]{0,63}") String code,
                                   @NotBlank @Pattern(regexp="XOF") String currency, @NotNull AccountType type) {
    public CreateAccountRequest {
        code = code == null ? null : code.strip().toUpperCase(Locale.ROOT);
        currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
    }
}
