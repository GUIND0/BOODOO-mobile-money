package com.paymentplatform.customer_service.kyc;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
public record KycDocument(@NotNull Type type,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String reference,
        @NotBlank String issuingCountry,LocalDate expiresOn) {
    public enum Type { NATIONAL_ID,PASSPORT,RESIDENCE_PERMIT }
    public KycDocument {
        reference=reference==null?null:reference.strip();
        issuingCountry=issuingCountry==null?null:issuingCountry.strip().toUpperCase(Locale.ROOT);
        if(issuingCountry!=null && !Set.of(Locale.getISOCountries()).contains(issuingCountry))throw new IllegalArgumentException("Unknown issuing country");
    }
    @Override public String toString(){return "KycDocument[REDACTED]";}
}
