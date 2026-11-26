package com.paymentplatform.ledger_service.journal;
import com.paymentplatform.ledger_service.api.AccountApiException;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.*;import java.time.*;import java.util.*;
@Service public class AccountStatementService {
 private final JdbcTemplate jdbc;public AccountStatementService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Line(UUID reference,String side,long amount){}
 public record Statement(UUID accountId,String currency,Instant from,Instant to,long opening,long closing,long entryCount,List<Line> entries,boolean complete,Instant checkedAt){}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10) public Statement snapshot(String tenant,UUID account,Instant from,Instant to){
  if(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0)throw error(HttpStatus.BAD_REQUEST,"INVALID_STATEMENT_PERIOD");
  var types=jdbc.query("SELECT account_type FROM ledger_accounts WHERE integrator_id=? AND id=?",(r,n)->r.getString(1),tenant,account);if(types.isEmpty())throw error(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND");if(!types.getFirst().equals("ASSET"))throw error(HttpStatus.CONFLICT,"SETTLEMENT_ASSET_REQUIRED");
  OffsetDateTime start=from.atOffset(ZoneOffset.UTC),end=to.atOffset(ZoneOffset.UTC);
  var totals=jdbc.queryForObject("""
   SELECT COALESCE(sum(CASE WHEN j.created_at<? THEN CASE WHEN e.side='DEBIT' THEN e.amount::numeric ELSE -e.amount::numeric END ELSE 0 END),0),
   COALESCE(sum(CASE WHEN e.side='DEBIT' THEN e.amount::numeric ELSE -e.amount::numeric END),0),count(*) FILTER(WHERE j.created_at>=?)
   FROM journal_entries e JOIN journals j ON j.id=e.journal_id AND j.integrator_id=e.integrator_id WHERE e.integrator_id=? AND e.account_id=? AND j.created_at<?
   """,(r,n)->new long[]{r.getBigDecimal(1).longValueExact(),r.getBigDecimal(2).longValueExact(),r.getLong(3)},start,start,tenant,account,end);
  var entries=jdbc.query("SELECT j.reference,e.side,e.amount FROM journal_entries e JOIN journals j ON j.id=e.journal_id AND j.integrator_id=e.integrator_id WHERE e.integrator_id=? AND e.account_id=? AND j.created_at>=? AND j.created_at<? ORDER BY j.created_at,j.id LIMIT 500",(r,n)->new Line(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)),tenant,account,start,end);
  Instant checked=Instant.now();return new Statement(account,"XOF",from,to,totals[0],totals[1],totals[2],entries,totals[2]<=500&&!to.isAfter(checked.minusSeconds(60)),checked);
 }
 private AccountApiException error(HttpStatus s,String c){return new AccountApiException(s,c,"The accounting statement cannot be generated.");}
}
