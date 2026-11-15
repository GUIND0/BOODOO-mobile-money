package com.paymentplatform.customer_service.customer;

import com.paymentplatform.customer_service.api.CreateCustomerRequest;
import com.paymentplatform.customer_service.api.CustomerApiException;
import com.paymentplatform.customer_service.api.CustomerResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class CustomerCreationService {
    private final CustomerRepository repository;
    private final ObjectMapper json;

    public CustomerCreationService(CustomerRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional(timeout = 10)
    public Creation create(String integrator, String key, CreateCustomerRequest request) {
        String fingerprint = fingerprint(request);
        repository.lockCreation(integrator, key);
        var saved = repository.findCreation(integrator, key);
        if (saved.isPresent()) {
            if (!saved.get().fingerprint().equals(fingerprint)) {
                throw new CustomerApiException("IDEMPOTENCY_CONFLICT",
                        "This Idempotency-Key was already used with a different customer profile.");
            }
            return new Creation(json.readValue(saved.get().responseJson(), CustomerResponse.class), true);
        }
        CustomerResponse customer = repository.insert(integrator, request).orElseThrow(() ->
                new CustomerApiException("CUSTOMER_ALREADY_EXISTS",
                        "A customer with this external reference or phone number already exists for this integrator."));
        repository.saveCreation(integrator, key, fingerprint, customer, json.writeValueAsString(customer));
        return new Creation(customer, false);
    }

    private String fingerprint(CreateCustomerRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Fixed V1 field order and byte-length prefixes keep fingerprints independent of JSON formatting.
            for (String value : List.of(request.externalReference(), request.firstName(),
                    request.lastName(), request.phoneNumber())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required digest algorithm unavailable", exception);
        }
    }

    public record Creation(CustomerResponse customer, boolean replayed) { }
}
