package com.paymentplatform.transfer_service.domain;
import jakarta.validation.constraints.*;import java.util.UUID;
public record TransferRequest(@NotNull UUID senderWalletId,@NotNull UUID recipientWalletId,@NotNull @Positive Long amount,@NotNull @PositiveOrZero Long maxFee,@NotBlank @Pattern(regexp="XOF") String currency,@NotBlank @Pattern(regexp="[0-9]{6}") String pin){
 @AssertTrue public boolean isDifferentWallets(){return senderWalletId==null||recipientWalletId==null||!senderWalletId.equals(recipientWalletId);}
 @Override public String toString(){return "TransferRequest[REDACTED]";}
 public Canonical canonical(){return new Canonical(senderWalletId,recipientWalletId,amount,maxFee,currency);}
 public record Canonical(UUID senderWalletId,UUID recipientWalletId,long amount,long maxFee,String currency){}
}
