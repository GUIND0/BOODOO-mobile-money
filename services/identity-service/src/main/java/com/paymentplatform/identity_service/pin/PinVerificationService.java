package com.paymentplatform.identity_service.pin;

import com.paymentplatform.identity_service.customer.CustomerOwnershipClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PinVerificationService {
    private final CustomerOwnershipClient customers;
    private final PinVerificationStore store;

    public PinVerificationService(CustomerOwnershipClient customers, PinVerificationStore store) {
        this.customers = customers;
        this.store = store;
    }

    public void verify(String integrator, UUID customer, String pin, String bearer) {
        customers.requireOwnership(customer, bearer);
        store.verify(integrator, customer, pin);
    }
}
