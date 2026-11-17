package com.paymentplatform.identity_service.pin;

import com.paymentplatform.identity_service.customer.CustomerOwnershipClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PinCreationService {
    private final CustomerOwnershipClient customers;
    private final PinSetupLimiter limiter;
    private final PinStoreService store;

    public PinCreationService(CustomerOwnershipClient customers, PinSetupLimiter limiter, PinStoreService store) {
        this.customers = customers;
        this.limiter = limiter;
        this.store = store;
    }

    public PinStoreService.Creation create(String integrator, UUID customer, String key, String pin, String bearer) {
        customers.requireOwnership(customer, bearer);
        limiter.consume(integrator, customer);
        return store.create(integrator, customer, key, pin);
    }
}
