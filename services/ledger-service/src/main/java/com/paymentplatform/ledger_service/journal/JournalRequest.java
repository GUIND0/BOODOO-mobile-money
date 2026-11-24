package com.paymentplatform.ledger_service.journal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record JournalRequest(@NotNull UUID reference,@NotNull @Size(min=2,max=20) List<@NotNull @Valid Entry> entries) {
 public enum Side { DEBIT,CREDIT }
 public record Entry(@NotNull UUID accountId,@NotNull Side side,@NotNull @Positive Long amount) { }
}
