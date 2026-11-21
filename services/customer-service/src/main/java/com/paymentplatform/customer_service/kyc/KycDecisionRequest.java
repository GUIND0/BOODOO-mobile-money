package com.paymentplatform.customer_service.kyc;
import jakarta.validation.constraints.*;
public record KycDecisionRequest(@NotNull Decision decision,
        @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode) {
    public enum Decision {APPROVED,REJECTED}
    public KycDecisionRequest {reasonCode=reasonCode==null?null:reasonCode.strip();}
}
