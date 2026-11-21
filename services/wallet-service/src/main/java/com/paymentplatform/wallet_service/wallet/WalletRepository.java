package com.paymentplatform.wallet_service.wallet;

import com.paymentplatform.wallet_service.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {
    private final JdbcTemplate jdbc;
    public WalletRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void lock(String tenant, String key) {
        String name = tenant.length() + ":" + tenant + key;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) row -> { }, name);
    }
    public Optional<Saved> findRequest(String tenant, String key) {
        return jdbc.query("SELECT request_fingerprint, response_snapshot::text FROM wallet_creation_requests WHERE integrator_id=? AND idempotency_key=?",
                (row, index) -> new Saved(row.getString(1), row.getString(2)), tenant, key).stream().findFirst();
    }
    public Optional<WalletResponse> insert(String tenant, CreateWalletRequest request) {
        return jdbc.query("""
                INSERT INTO wallets(id,integrator_id,customer_id,currency) VALUES (?,?,?,?) ON CONFLICT DO NOTHING RETURNING *
                """, (row, index) -> read(row), UUID.randomUUID(), tenant, request.customerId(), request.currency()).stream().findFirst();
    }
    public Optional<WalletResponse> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM wallets WHERE integrator_id=? AND id=?", (row, index) -> read(row), tenant, id).stream().findFirst();
    }
    public void saveRequest(String tenant, String key, String fingerprint, UUID wallet, String snapshot) {
        jdbc.update("INSERT INTO wallet_creation_requests(integrator_id,idempotency_key,request_fingerprint,wallet_id,response_snapshot) VALUES (?,?,?,?,?::jsonb)",
                tenant, key, fingerprint, wallet, snapshot);
    }
    private WalletResponse read(ResultSet row) throws SQLException {
        return new WalletResponse(row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getString("currency"), row.getString("status"), row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
    public record Saved(String fingerprint, String snapshot) { }
}
