package com.paymentplatform.reconciliation_service.domain;
import com.paymentplatform.reconciliation_service.api.ApiException;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.util.*;import java.time.*;
@Service public class ReportStore {
 private final JdbcTemplate jdbc;private final ObjectMapper json;public ReportStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public record Report(UUID id,String status,long journalCount,long unbalancedJournals,long invalidBalances,Instant ledgerCheckedAt,Instant completedAt){}
 public record Result(Report response,boolean replayed){}
 public Optional<Report> key(String tenant,String actor,String key){var rows=jdbc.query("SELECT actor_id,snapshot::text FROM reconciliation_reports WHERE integrator_id=? AND idempotency_key=?",(row,n)->List.of(row.getString(1),row.getString(2)),tenant,key);if(rows.isEmpty())return Optional.empty();if(!rows.getFirst().getFirst().equals(actor))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This check key belongs to another service subject.");return Optional.of(json.readValue(rows.getFirst().get(1),Report.class));}
 @Transactional(timeout=10) public Result save(String tenant,String actor,String key,long count,long unbalanced,long invalid,Instant checkedAt){
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)row->{},tenant.length()+":"+tenant+key);var prior=key(tenant,actor,key);if(prior.isPresent())return new Result(prior.get(),true);
  Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();var result=new Report(UUID.randomUUID(),unbalanced==0&&invalid==0?"CONSISTENT":"DISCREPANCY",count,unbalanced,invalid,checkedAt,now);
  jdbc.update("INSERT INTO reconciliation_reports VALUES (?,?,?,?,?::jsonb)",result.id(),tenant,key,actor,json.writeValueAsString(result));return new Result(result,false);
 }
 public Report get(String tenant,UUID id){return jdbc.query("SELECT snapshot::text FROM reconciliation_reports WHERE integrator_id=? AND id=?",(row,n)->json.readValue(row.getString(1),Report.class),tenant,id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","Report not found for this integrator."));}
}
