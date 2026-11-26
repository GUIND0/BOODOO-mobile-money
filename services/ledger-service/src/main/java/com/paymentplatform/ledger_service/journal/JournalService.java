package com.paymentplatform.ledger_service.journal;
import com.paymentplatform.ledger_service.api.AccountApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.math.BigInteger;
import java.security.*;
import java.nio.charset.StandardCharsets;
@Service
public class JournalService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public JournalService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 @Transactional(timeout=15)
 public Result post(String tenant,String actor,String key,JournalRequest input){
  referenceLock(tenant,input.reference());
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM journal_cancellations WHERE integrator_id=? AND reference=?)",Boolean.class,tenant,input.reference())))throw error(HttpStatus.CONFLICT,"JOURNAL_CANCELLED");
  var entries=input.entries().stream().sorted(Comparator.comparing(e->e.accountId().toString())).toList();
  if(entries.stream().map(JournalRequest.Entry::accountId).distinct().count()!=entries.size())throw error(HttpStatus.BAD_REQUEST,"DUPLICATE_ACCOUNT");
  BigInteger debit=BigInteger.ZERO,credit=BigInteger.ZERO;
  for(var entry:entries){if(entry.side()==JournalRequest.Side.DEBIT)debit=debit.add(BigInteger.valueOf(entry.amount()));else credit=credit.add(BigInteger.valueOf(entry.amount()));}
  if(!debit.equals(credit)||debit.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)throw error(HttpStatus.BAD_REQUEST,"UNBALANCED_JOURNAL");
  var canonical=new JournalRequest(input.reference(),entries);
  String hash=hash(json.writeValueAsString(canonical)+"\n"+actor);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)row->{},tenant.length()+":"+tenant+key);
  var saved=jdbc.query("SELECT fingerprint,snapshot::text FROM journal_requests WHERE integrator_id=? AND idempotency_key=?",(row,n)->List.of(row.getString(1),row.getString(2)),tenant,key);
  if(!saved.isEmpty()){if(!saved.getFirst().getFirst().equals(hash))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");return new Result(json.readValue(saved.getFirst().get(1),Receipt.class),true);}
  var args=new ArrayList<Object>();args.add(tenant);entries.forEach(e->args.add(e.accountId()));
  var accounts=jdbc.query("SELECT id,normal_balance FROM ledger_accounts WHERE integrator_id=? AND id IN ("+String.join(",",Collections.nCopies(entries.size(),"?"))+") ORDER BY id FOR UPDATE",
   (row,n)->new Account(row.getObject(1,UUID.class),row.getString(2)),args.toArray());
  if(accounts.size()!=entries.size())throw error(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND");
  for(var account:accounts){
   if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_controls WHERE integrator_id=? AND account_id=? AND state<>'OPEN')",Boolean.class,tenant,account.id())))throw error(HttpStatus.CONFLICT,"ACCOUNT_RESTRICTED");
   var entry=entries.stream().filter(e->e.accountId().equals(account.id())).findFirst().orElseThrow();
   long before=amount(tenant,account.id(),account.normal());long delta=entry.side().name().equals(account.normal())?entry.amount():-entry.amount();
   try{if(Math.addExact(before,delta)<0)throw error(HttpStatus.CONFLICT,"INSUFFICIENT_BALANCE");}catch(ArithmeticException exception){throw error(HttpStatus.CONFLICT,"BALANCE_OVERFLOW");}}
  UUID id=UUID.randomUUID();var inserted=jdbc.query("INSERT INTO journals(id,integrator_id,reference,created_by) VALUES (?,?,?,?) ON CONFLICT DO NOTHING RETURNING created_at",
   (row,n)->row.getObject(1,OffsetDateTime.class).toInstant(),id,tenant,input.reference(),actor);
  if(inserted.isEmpty())throw error(HttpStatus.CONFLICT,"JOURNAL_REFERENCE_EXISTS");
  for(var entry:entries)jdbc.update("INSERT INTO journal_entries VALUES (?,?,?,?,?)",tenant,id,entry.accountId(),entry.side().name(),entry.amount());
  var receipt=new Receipt(id,input.reference(),"XOF",inserted.getFirst(),entries);
  jdbc.update("INSERT INTO journal_requests VALUES (?,?,?,?,?::jsonb)",tenant,key,hash,id,json.writeValueAsString(receipt));return new Result(receipt,false);
 }
 private void referenceLock(String tenant,UUID reference){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)r->{},"journal-reference:"+tenant+":"+reference);}
 @Transactional(timeout=15)
 public Cancellation cancel(String tenant,String actor,UUID reference){
  referenceLock(tenant,reference);
  var posted=jdbc.query("SELECT id FROM journals WHERE integrator_id=? AND reference=?",(r,n)->r.getObject(1,UUID.class),tenant,reference);
  if(!posted.isEmpty())return new Cancellation(reference,"POSTED",posted.getFirst());
  var owners=jdbc.query("SELECT cancelled_by FROM journal_cancellations WHERE integrator_id=? AND reference=?",(r,n)->r.getString(1),tenant,reference);
  if(!owners.isEmpty()&&!owners.getFirst().equals(actor))throw error(HttpStatus.CONFLICT,"CANCELLATION_OWNER_CONFLICT");
  jdbc.update("INSERT INTO journal_cancellations(integrator_id,reference,cancelled_by) VALUES (?,?,?) ON CONFLICT DO NOTHING",tenant,reference,actor);
  return new Cancellation(reference,"CANCELLED",null);
 }
 public record Cancellation(UUID reference,String status,UUID journalId){}
 public Receipt get(String tenant,UUID id){return jdbc.query("SELECT snapshot::text FROM journal_requests WHERE integrator_id=? AND journal_id=?",(row,n)->json.readValue(row.getString(1),Receipt.class),tenant,id).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"JOURNAL_NOT_FOUND"));}
 public Receipt byReference(String tenant,UUID reference){return jdbc.query("SELECT r.snapshot::text FROM journal_requests r JOIN journals j ON j.id=r.journal_id AND j.integrator_id=r.integrator_id WHERE j.integrator_id=? AND j.reference=?",(row,n)->json.readValue(row.getString(1),Receipt.class),tenant,reference).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"JOURNAL_NOT_FOUND"));}
 public Balance balance(String tenant,UUID id){return jdbc.query("""
  SELECT a.normal_balance,COALESCE(sum(CASE WHEN e.side=a.normal_balance THEN e.amount::numeric ELSE -e.amount::numeric END),0) AS amount
  FROM ledger_accounts a LEFT JOIN journal_entries e ON e.account_id=a.id AND e.integrator_id=a.integrator_id
  WHERE a.integrator_id=? AND a.id=? GROUP BY a.id,a.normal_balance
  """,(row,n)->new Balance(id,"XOF",row.getString(1),row.getBigDecimal(2).longValueExact()),tenant,id).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND"));}
 private long amount(String tenant,UUID id,String normal){return jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN side=? THEN amount::numeric ELSE -amount::numeric END),0) FROM journal_entries WHERE integrator_id=? AND account_id=?",java.math.BigDecimal.class,normal,tenant,id).longValueExact();}
 public Reconciliation reconcile(String tenant){return jdbc.queryForObject("""
  WITH totals AS (SELECT j.id,count(e.account_id) AS n,COALESCE(sum(CASE WHEN e.side='DEBIT' THEN e.amount::numeric ELSE -e.amount::numeric END),0) AS delta FROM journals j LEFT JOIN journal_entries e ON e.journal_id=j.id AND e.integrator_id=j.integrator_id WHERE j.integrator_id=? GROUP BY j.id),
  balances AS (SELECT a.id,COALESCE(sum(CASE WHEN e.side=a.normal_balance THEN e.amount::numeric ELSE -e.amount::numeric END),0) AS amount FROM ledger_accounts a LEFT JOIN journal_entries e ON e.account_id=a.id AND e.integrator_id=a.integrator_id WHERE a.integrator_id=? GROUP BY a.id,a.normal_balance)
  SELECT (SELECT count(*) FROM totals),(SELECT count(*) FROM totals WHERE n<2 OR delta<>0),(SELECT count(*) FROM balances WHERE amount<0 OR amount>9223372036854775807),CURRENT_TIMESTAMP
  """,(row,n)->new Reconciliation(row.getLong(1),row.getLong(2),row.getLong(3),row.getObject(4,OffsetDateTime.class).toInstant()),tenant,tenant);}
 private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private AccountApiException error(HttpStatus status,String code){return new AccountApiException(status,code,"The journal operation could not be accepted.");}
 private record Account(UUID id,String normal){}
 public record Receipt(UUID id,UUID reference,String currency,Instant createdAt,List<JournalRequest.Entry> entries){}
 public record Result(Receipt receipt,boolean replayed){}
 public record Balance(UUID accountId,String currency,String normalBalance,long amountMinorUnits){}
 public record Reconciliation(long journalCount,long unbalancedJournals,long invalidBalances,Instant checkedAt){}
}
