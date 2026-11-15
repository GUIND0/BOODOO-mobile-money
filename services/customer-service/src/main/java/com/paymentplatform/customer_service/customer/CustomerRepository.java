package com.paymentplatform.customer_service.customer;

import com.paymentplatform.customer_service.api.CreateCustomerRequest;
import com.paymentplatform.customer_service.api.CustomerResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CustomerRepository {
    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockCreation(String integrator, String key) {
        // Length-prefix the tenant so different tenant/key pairs cannot share an ambiguous lock name.
        String lockName = integrator.length() + ":" + integrator + key;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) row -> { }, lockName);
    }

    public Optional<SavedCreation> findCreation(String integrator, String key) {
        return jdbc.query("""
                SELECT request_fingerprint, creation_response::text FROM customer_creation_requests
                WHERE integrator_id = ? AND idempotency_key = ?
                """, (row, index) -> new SavedCreation(row.getString(1), row.getString(2)), integrator, key)
                .stream().findFirst();
    }

    public Optional<CustomerResponse> insert(String integrator, CreateCustomerRequest request) {
        return jdbc.query("""
                INSERT INTO customers (id, integrator_id, external_reference, first_name, last_name, phone_number)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                RETURNING id, external_reference, first_name, last_name, phone_number, kyc_status, created_at
                """, (row, index) -> new CustomerResponse(row.getObject("id", UUID.class),
                row.getString("external_reference"), row.getString("first_name"), row.getString("last_name"),
                row.getString("phone_number"), row.getString("kyc_status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant()),
                UUID.randomUUID(), integrator, request.externalReference(), request.firstName(),
                request.lastName(), request.phoneNumber()).stream().findFirst();
    }

    public void saveCreation(String integrator, String key, String fingerprint,
                             CustomerResponse customer, String responseJson) {
        jdbc.update("""
                INSERT INTO customer_creation_requests
                  (integrator_id, idempotency_key, request_fingerprint, customer_id, creation_response)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, integrator, key, fingerprint, customer.id(), responseJson);
    }

    public record SavedCreation(String fingerprint, String responseJson) { }
}
