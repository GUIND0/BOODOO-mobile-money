package com.paymentplatform.identity_service.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record VerifyPinRequest(@NotNull JsonNode pin) {
    @AssertTrue(message = "Supply the six-digit PIN as a JSON string")
    public boolean isValidPin() {
        return pin != null && pin.isString() && pin.asString().matches("[0-9]{6}");
    }

    public String value() { return pin.asString(); }

    @Override
    public String toString() { return "VerifyPinRequest[REDACTED]"; }
}
