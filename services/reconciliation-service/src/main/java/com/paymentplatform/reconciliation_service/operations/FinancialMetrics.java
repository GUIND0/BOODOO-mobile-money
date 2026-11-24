package com.paymentplatform.reconciliation_service.operations;
import io.micrometer.core.instrument.Gauge;import io.micrometer.core.instrument.MeterRegistry;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Component;
@Component public class FinancialMetrics {
 public FinancialMetrics(JdbcTemplate jdbc,MeterRegistry registry) {
  Gauge.builder("boodoo.reconciliation.discrepancies",jdbc,j -> j.queryForObject("SELECT count(*) FROM external_statements WHERE snapshot->>'status'<>'MATCHED'",java.math.BigDecimal.class).doubleValue()).register(registry);
 }
}
