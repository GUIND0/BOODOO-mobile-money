package com.paymentplatform.identity_service.api;

import java.time.Instant;
import java.util.UUID;

public record PinResponse(UUID customerId, String status, Instant createdAt) { }
