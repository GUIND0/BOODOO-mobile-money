package com.paymentplatform.identity_service.pin;

import com.paymentplatform.identity_service.api.PinResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PinRepository {
    private final JdbcTemplate jdbc;

    public PinRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(String kind, String integrator, String value) {
        String name = kind + ":" + integrator.length() + ":" + integrator + value;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) row -> { }, name);
    }

    public Optional<SavedCreation> findCreation(String integrator, String key) {
        return jdbc.query("""
                SELECT r.customer_id, r.request_hmac, r.pepper_version, p.created_at
                FROM pin_creation_requests r JOIN customer_pins p
                  ON p.integrator_id = r.integrator_id AND p.customer_id = r.customer_id
                WHERE r.integrator_id = ? AND r.idempotency_key = ?
                """, (row, index) -> new SavedCreation(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                row.getObject(4, OffsetDateTime.class).toInstant()), integrator, key).stream().findFirst();
    }

    public Optional<Credential> findCredential(String integrator, UUID customer) {
        return jdbc.query("SELECT pin_hash, pepper_version FROM customer_pins WHERE integrator_id = ? AND customer_id = ?",
                (row, index) -> new Credential(row.getString(1), row.getString(2)), integrator, customer).stream().findFirst();
    }

    public record Credential(String hash, String version) {
        @Override
        public String toString() { return "Credential[REDACTED]"; }
    }

    public boolean exists(String integrator, UUID customer) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM customer_pins WHERE integrator_id = ? AND customer_id = ?)",
                Boolean.class, integrator, customer));
    }

    public PinResponse insert(String integrator, UUID customer, String hash, String version) {
        return jdbc.queryForObject("""
                INSERT INTO customer_pins (integrator_id, customer_id, pin_hash, pepper_version) VALUES (?, ?, ?, ?)
                RETURNING created_at
                """, (row, index) -> new PinResponse(customer, "CREATED", row.getObject(1, OffsetDateTime.class).toInstant()),
                integrator, customer, hash, version);
    }

    public void saveCreation(String integrator, String key, UUID customer, String fingerprint, String version) {
        jdbc.update("""
                INSERT INTO pin_creation_requests (integrator_id, idempotency_key, customer_id, request_hmac, pepper_version)
                VALUES (?, ?, ?, ?, ?)
                """, integrator, key, customer, fingerprint, version);
    }

    public record SavedCreation(UUID customer, String fingerprint, String version, java.time.Instant createdAt) { }
}
