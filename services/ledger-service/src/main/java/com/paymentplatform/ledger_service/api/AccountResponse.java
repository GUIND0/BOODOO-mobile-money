package com.paymentplatform.ledger_service.api;
import com.paymentplatform.ledger_service.account.AccountType;
import java.time.Instant;
import java.util.UUID;
public record AccountResponse(UUID id, String code, String currency, AccountType type, String normalBalance, Instant createdAt) { }
