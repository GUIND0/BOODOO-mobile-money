package com.paymentplatform.wallet_service.wallet;

import com.paymentplatform.wallet_service.api.*;
import com.paymentplatform.wallet_service.customer.CustomerOwnershipClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class WalletService {
    private final CustomerOwnershipClient customers;
    private final WalletCreationStore store;
    private final WalletRepository wallets;
    public WalletService(CustomerOwnershipClient customers, WalletCreationStore store, WalletRepository wallets) {
        this.customers = customers; this.store = store; this.wallets = wallets;
    }
    public WalletCreationStore.Creation create(String tenant, String key, CreateWalletRequest request, String bearer) {
        if (!"XOF".equals(request.currency())) throw new WalletApiException(HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_CURRENCY", "V1 wallet provisioning supports XOF.");
        // Verify remote ownership before entering the database transaction, including on replay.
        customers.requireOwnership(request.customerId(), bearer);
        return store.create(tenant, key, request);
    }
    public WalletResponse get(String tenant, UUID id, String bearer) {
        var wallet = wallets.find(tenant, id).orElseThrow(() -> new WalletApiException(HttpStatus.NOT_FOUND,
                "WALLET_NOT_FOUND", "Wallet not found for this integrator."));
        customers.requireOwnership(wallet.customerId(), bearer);
        return wallet;
    }
}
