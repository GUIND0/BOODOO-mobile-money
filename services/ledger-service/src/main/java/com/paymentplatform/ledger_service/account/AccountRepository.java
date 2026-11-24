package com.paymentplatform.ledger_service.account;
import com.paymentplatform.ledger_service.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;
    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void lock(String tenant, String key) {
        String value = tenant.length() + ":" + tenant + key;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (RowCallbackHandler) row -> { }, value);
    }
    public Optional<Saved> findRequest(String tenant, String key) {
        return jdbc.query("SELECT request_fingerprint,response_snapshot::text FROM account_creation_requests WHERE integrator_id=? AND idempotency_key=?",
                (row,index)->new Saved(row.getString(1),row.getString(2)),tenant,key).stream().findFirst();
    }
    public Optional<AccountResponse> insert(String tenant, CreateAccountRequest request, String actor) {
        return jdbc.query("""
                INSERT INTO ledger_accounts(id,integrator_id,code,currency,account_type,normal_balance,created_by)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING *
                """, (row,index)->read(row),UUID.randomUUID(),tenant,request.code(),request.currency(),request.type().name(),
                request.type().normalBalance(),actor).stream().findFirst();
    }
    public Optional<AccountResponse> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM ledger_accounts WHERE integrator_id=? AND id=?",(row,index)->read(row),tenant,id).stream().findFirst();
    }
    public void save(String tenant, String key, String fingerprint, AccountResponse response, String snapshot) {
        jdbc.update("INSERT INTO account_creation_requests(integrator_id,idempotency_key,request_fingerprint,account_id,response_snapshot) VALUES (?,?,?,?,?::jsonb)",
                tenant,key,fingerprint,response.id(),snapshot);
    }
    private AccountResponse read(ResultSet row) throws SQLException {
        return new AccountResponse(row.getObject("id",UUID.class),row.getString("code"),row.getString("currency"),
                AccountType.valueOf(row.getString("account_type")),row.getString("normal_balance"),
                row.getObject("created_at",OffsetDateTime.class).toInstant());
    }
    public record Saved(String fingerprint, String snapshot) { }
}
