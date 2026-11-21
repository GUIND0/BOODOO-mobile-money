package com.paymentplatform.customer_service.customer;

import com.paymentplatform.customer_service.api.CustomerDirectoryController.Entry;
import com.paymentplatform.customer_service.api.CustomerDirectoryController.KycStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class CustomerDirectoryRepository {
    private final JdbcTemplate jdbc;
    public CustomerDirectoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Entry> findPage(String tenant, UUID after, KycStatus kycStatus, int limit) {
        return jdbc.query("""
                SELECT id, external_reference, first_name, last_name, kyc_status, created_at
                FROM customers WHERE integrator_id = ?
                  AND (?::uuid IS NULL OR id > ?::uuid)
                  AND (?::text IS NULL OR kyc_status = ?)
                ORDER BY id LIMIT ?
                """, (row, index) -> new Entry(row.getObject("id", UUID.class), row.getString("external_reference"),
                row.getString("first_name"), row.getString("last_name"), row.getString("kyc_status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant()),
                tenant, after, after, kycStatus == null ? null : kycStatus.name(),
                kycStatus == null ? null : kycStatus.name(), limit + 1);
    }
}
