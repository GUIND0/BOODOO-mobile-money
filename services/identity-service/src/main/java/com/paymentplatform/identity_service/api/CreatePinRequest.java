package com.paymentplatform.identity_service.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record CreatePinRequest(@NotNull JsonNode pin) {
    @AssertTrue(message = "Use a non-trivial six-digit PIN supplied as a JSON string")
    public boolean isValidPin() {
        if (pin == null || !pin.isString() || !pin.asString().matches("[0-9]{6}")) return false;
        String value = pin.asString();
        boolean repeated = value.chars().allMatch(c -> c == value.charAt(0));
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < value.length(); i++) {
            ascending &= value.charAt(i) - '0' == (value.charAt(i - 1) - '0' + 1) % 10;
            descending &= value.charAt(i) - '0' == (value.charAt(i - 1) - '0' + 9) % 10;
        }
        return !repeated && !ascending && !descending;
    }

    public String value() { return pin.asString(); }

    @Override
    public String toString() { return "CreatePinRequest[REDACTED]"; }
}
