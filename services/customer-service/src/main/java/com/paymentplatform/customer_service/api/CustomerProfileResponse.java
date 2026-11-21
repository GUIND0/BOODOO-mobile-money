package com.paymentplatform.customer_service.api;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
public record CustomerProfileResponse(UUID id, String externalReference, String firstName, String lastName,
        String phoneNumber, String kycStatus, Instant createdAt, long version, Instant updatedAt,
        LocalDate dateOfBirth, String nationality, String residenceCountry, String addressLine, String city) {
    @Override public String toString(){return "CustomerProfileResponse[REDACTED]";}
}
