package com.paymentplatform.customer_service.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 128) String externalReference,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Pattern(regexp = "\\+[1-9][0-9]{1,14}") String phoneNumber) {

    public CreateCustomerRequest {
        externalReference = normalize(externalReference);
        firstName = normalize(firstName);
        lastName = normalize(lastName);
        phoneNumber = normalize(phoneNumber);
    }

    private static String normalize(String value) {
        return value == null ? null : value.strip();
    }
}
