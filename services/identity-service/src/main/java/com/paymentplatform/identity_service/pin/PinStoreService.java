package com.paymentplatform.identity_service.pin;

import com.paymentplatform.identity_service.api.PinApiException;
import com.paymentplatform.identity_service.api.PinResponse;
import com.paymentplatform.identity_service.security.PinProtector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Service
public class PinStoreService {
    private final PinRepository repository;
    private final PinProtector protector;

    public PinStoreService(PinRepository repository, PinProtector protector) {
        this.repository = repository;
        this.protector = protector;
    }

    @Transactional(timeout = 10)
    public Creation create(String integrator, UUID customer, String key, String pin) {
        repository.lock("pin-key", integrator, key);
        var saved = repository.findCreation(integrator, key);
        if (saved.isPresent()) {
            var previous = saved.get();
            if (!previous.version().equals(protector.version())) throw new PinApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PIN_KEY_UNAVAILABLE", "The PIN protection key version is unavailable for this request.");
            if (!previous.customer().equals(customer) || !MessageDigest.isEqual(
                    previous.fingerprint().getBytes(StandardCharsets.US_ASCII),
                    protector.fingerprint(integrator, customer, pin).getBytes(StandardCharsets.US_ASCII))) {
                throw new PinApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "This key was used with a different PIN setup request.");
            }
            return new Creation(new PinResponse(customer, "CREATED", previous.createdAt()), true);
        }
        repository.lock("pin-customer", integrator, customer.toString());
        if (repository.exists(integrator, customer)) throw new PinApiException(HttpStatus.CONFLICT,
                "PIN_ALREADY_EXISTS", "A PIN is already configured. Creation cannot replace it.");
        var response = repository.insert(integrator, customer, protector.encode(integrator, customer, pin), protector.version());
        repository.saveCreation(integrator, key, customer, protector.fingerprint(integrator, customer, pin), protector.version());
        return new Creation(response, false);
    }

    public record Creation(PinResponse response, boolean replayed) { }
}
