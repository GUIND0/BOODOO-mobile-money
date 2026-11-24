package com.paymentplatform.ledger_service.journal;
import com.paymentplatform.ledger_service.api.AccountApiException;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
@Service public class AccountControlService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final JournalService journals;
 public AccountControlService(JdbcTemplate jdbc,ObjectMapper json,JournalService journals){this.jdbc=jdbc;this.json=json;this.journals=journals;}
 public enum State {OPEN,FROZEN,CLOSED}
 public record Request(@NotNull UUID id,@NotNull State state,@Min(0) long expectedVersion,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reasonCode){}
 public record View(UUID accountId,State state,long version){}
 public View get(String tenant,UUID account){journals.balance(tenant,account);return jdbc.query("SELECT state,version FROM account_controls WHERE integrator_id=? AND account_id=?",(r,n)->new View(account,State.valueOf(r.getString(1)),r.getLong(2)),tenant,account).stream().findFirst().orElse(new View(account,State.OPEN,0));}
 @Transactional(timeout=15) public View change(String tenant,String actor,UUID account,Request request){
  var locked=jdbc.query("SELECT id FROM ledger_accounts WHERE integrator_id=? AND id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),tenant,account);if(locked.isEmpty())throw error(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND");
  String fingerprint=json.writeValueAsString(List.of(account,request,actor));
  var prior=jdbc.query("SELECT fingerprint,snapshot::text FROM account_control_events WHERE integrator_id=? AND id=?",(r,n)->List.of(r.getString(1),r.getString(2)),tenant,request.id());
  if(!prior.isEmpty()){if(!prior.getFirst().getFirst().equals(fingerprint))throw error(HttpStatus.CONFLICT,"CONTROL_ID_CONFLICT");return json.readValue(prior.getFirst().get(1),View.class);}
  var old=get(tenant,account);if(old.version()!=request.expectedVersion())throw error(HttpStatus.PRECONDITION_FAILED,"CONTROL_VERSION_CONFLICT");
  if(old.state()==State.CLOSED||old.state()==request.state())throw error(HttpStatus.CONFLICT,"CONTROL_STATE_CONFLICT");
  if(request.state()==State.CLOSED&&(old.state()!=State.FROZEN||journals.balance(tenant,account).amountMinorUnits()!=0))throw error(HttpStatus.CONFLICT,"FROZEN_EMPTY_ACCOUNT_REQUIRED");
  var view=new View(account,request.state(),old.version()+1);
  jdbc.update("INSERT INTO account_controls VALUES (?,?,?,?) ON CONFLICT(integrator_id,account_id) DO UPDATE SET state=EXCLUDED.state,version=EXCLUDED.version",tenant,account,view.state().name(),view.version());
  jdbc.update("INSERT INTO account_control_events(integrator_id,id,account_id,fingerprint,snapshot) VALUES (?,?,?,?,?::jsonb)",tenant,request.id(),account,fingerprint,json.writeValueAsString(view));return view;
 }
 private AccountApiException error(HttpStatus s,String code){return new AccountApiException(s,code,"The account lifecycle change cannot be accepted.");}
}
