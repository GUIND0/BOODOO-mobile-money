package com.paymentplatform.wallet_service.wallet;

import com.paymentplatform.wallet_service.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class WalletCreationStore {
    private final WalletRepository wallets;
    private final ObjectMapper json;
    public WalletCreationStore(WalletRepository wallets, ObjectMapper json) { this.wallets = wallets; this.json = json; }
    @Transactional(timeout = 10)
    public Creation create(String tenant, String key, CreateWalletRequest request) {
        String fingerprint = fingerprint(request);
        wallets.lock(tenant, key);
        var previous = wallets.findRequest(tenant, key);
        if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint)) throw new WalletApiException(HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT", "This key was already used with a different wallet request.");
            return new Creation(json.readValue(previous.get().snapshot(), WalletResponse.class), true);
        }
        var wallet = wallets.insert(tenant, request).orElseThrow(() -> new WalletApiException(HttpStatus.CONFLICT,
                "WALLET_ALREADY_EXISTS", "A wallet already exists for this customer and currency."));
        wallets.saveRequest(tenant, key, fingerprint, wallet.id(), json.writeValueAsString(wallet));
        return new Creation(wallet, false);
    }
    private String fingerprint(CreateWalletRequest request) {
        try {
            // UUID has a fixed representation and currency is exactly three ASCII letters.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((request.customerId() + ":" + request.currency()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("Required digest unavailable", error); }
    }
    public record Creation(WalletResponse response, boolean replayed) { }
}
