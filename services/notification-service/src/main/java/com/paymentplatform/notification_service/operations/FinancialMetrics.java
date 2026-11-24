package com.paymentplatform.notification_service.operations;
import io.micrometer.core.instrument.Gauge;import io.micrometer.core.instrument.MeterRegistry;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Component;
@Component public class FinancialMetrics {
 public FinancialMetrics(JdbcTemplate jdbc,MeterRegistry registry) {
  Gauge.builder("boodoo.webhooks.dead",jdbc,j -> j.queryForObject("SELECT count(*) FROM webhook_outbox WHERE state='DEAD'",java.math.BigDecimal.class).doubleValue()).register(registry);
  Gauge.builder("boodoo.webhooks.pending",jdbc,j -> j.queryForObject("SELECT count(*) FROM webhook_outbox WHERE state IN ('READY','INFLIGHT')",java.math.BigDecimal.class).doubleValue()).register(registry);
 }
}
