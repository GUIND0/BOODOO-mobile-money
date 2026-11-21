package com.paymentplatform.customer_service.customer;

import com.paymentplatform.customer_service.api.CustomerProfileHistoryController.Entry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class CustomerProfileHistoryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CustomerProfileHistoryRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public List<Entry> findPage(String tenant, UUID customer, long afterVersion, int limit) {
        return jdbc.query("""
                SELECT profile_version, actor_id, changed_fields, changed_at FROM customer_profile_updates
                WHERE integrator_id = ? AND customer_id = ? AND profile_version > ?
                ORDER BY profile_version LIMIT ?
                """, (row, index) -> new Entry(row.getLong("profile_version"), row.getString("actor_id"),
                List.of(json.readValue(row.getString("changed_fields"), String[].class)),
                row.getObject("changed_at", OffsetDateTime.class).toInstant()), tenant, customer, afterVersion, limit + 1);
    }
}
