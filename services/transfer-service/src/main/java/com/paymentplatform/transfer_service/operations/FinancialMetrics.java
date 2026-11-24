package com.paymentplatform.transfer_service.operations;
import io.micrometer.core.instrument.Gauge;import io.micrometer.core.instrument.MeterRegistry;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Component;
@Component public class FinancialMetrics {
 public FinancialMetrics(JdbcTemplate jdbc,MeterRegistry registry) {
  Gauge.builder("boodoo.transfers.pending",jdbc,j -> j.queryForObject("SELECT count(*) FROM transfers WHERE state NOT IN ('COMPLETED','REJECTED','CANCELLED')",java.math.BigDecimal.class).doubleValue()).register(registry);
  Gauge.builder("boodoo.transfers.oldest.seconds",jdbc,j -> j.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at)),0) FROM transfers WHERE state NOT IN ('CREATED','COMPLETED','REJECTED','CANCELLED')",java.math.BigDecimal.class).doubleValue()).register(registry);
 }
}
