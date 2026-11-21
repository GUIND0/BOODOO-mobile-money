package com.paymentplatform.wallet_service.api;
import java.time.Instant;
import java.util.UUID;
public record WalletResponse(UUID id, UUID customerId, String currency, String status, Instant createdAt) { }
