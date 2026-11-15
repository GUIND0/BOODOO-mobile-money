package com.paymentplatform.customer_service.api;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(UUID id, String externalReference, String firstName, String lastName,
                               String phoneNumber, String kycStatus, Instant createdAt) {
}
