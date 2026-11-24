package com.paymentplatform.ledger_service.journal;
import com.paymentplatform.ledger_service.api.AccountApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class ReversalService {
 private final JdbcTemplate jdbc; private final JournalService journals;
 public ReversalService(JdbcTemplate jdbc,JournalService journals){this.jdbc=jdbc;this.journals=journals;}
 public record View(UUID id,UUID originalJournalId,String reasonCode,String requestedBy,String approvedBy,UUID compensatingJournalId){}
 private View read(java.sql.ResultSet r)throws java.sql.SQLException{return new View(r.getObject("id",UUID.class),r.getObject("original_journal_id",UUID.class),r.getString("reason_code"),r.getString("requested_by"),r.getString("approved_by"),r.getObject("compensating_journal_id",UUID.class));}
 public View get(String tenant,UUID id){return jdbc.query("SELECT * FROM journal_reversals WHERE integrator_id=? AND id=?",(r,n)->read(r),tenant,id).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"REVERSAL_NOT_FOUND"));}
 @Transactional(timeout=15)
 public View request(String tenant,String actor,UUID original,String reason){
  journals.get(tenant,original);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)r->{},"reversal:"+tenant+":"+original);
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM journal_reversals WHERE integrator_id=? AND compensating_journal_id=?)",Boolean.class,tenant,original)))throw error(HttpStatus.CONFLICT,"REVERSAL_OF_REVERSAL_FORBIDDEN");
  var existing=jdbc.query("SELECT * FROM journal_reversals WHERE integrator_id=? AND original_journal_id=?",(r,n)->read(r),tenant,original);
  if(!existing.isEmpty()){var v=existing.getFirst();if(!v.requestedBy().equals(actor)||!v.reasonCode().equals(reason))throw error(HttpStatus.CONFLICT,"REVERSAL_ALREADY_REQUESTED");return v;}
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO journal_reversals(id,integrator_id,original_journal_id,reason_code,requested_by) VALUES (?,?,?,?,?)",id,tenant,original,reason,actor);return get(tenant,id);
 }
 @Transactional(timeout=15)
 public View approve(String tenant,String checker,UUID id){
  var rows=jdbc.query("SELECT * FROM journal_reversals WHERE integrator_id=? AND id=? FOR UPDATE",(r,n)->read(r),tenant,id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"REVERSAL_NOT_FOUND");var v=rows.getFirst();
  if(v.requestedBy().equals(checker))throw error(HttpStatus.FORBIDDEN,"SELF_APPROVAL_FORBIDDEN");
  if(v.compensatingJournalId()!=null){if(!checker.equals(v.approvedBy()))throw error(HttpStatus.CONFLICT,"REVERSAL_APPROVER_CONFLICT");return v;}
  var original=journals.get(tenant,v.originalJournalId());
  var entries=original.entries().stream().map(e->new JournalRequest.Entry(e.accountId(),e.side()==JournalRequest.Side.DEBIT?JournalRequest.Side.CREDIT:JournalRequest.Side.DEBIT,e.amount())).toList();
  var receipt=journals.post(tenant,"reversal:"+id,"reversal-"+id,new JournalRequest(id,entries)).receipt();
  jdbc.update("UPDATE journal_reversals SET approved_by=?,compensating_journal_id=?,approved_at=clock_timestamp() WHERE integrator_id=? AND id=?",checker,receipt.id(),tenant,id);return get(tenant,id);
 }
 private AccountApiException error(HttpStatus status,String code){return new AccountApiException(status,code,"The linked reversal cannot be accepted.");}
}
