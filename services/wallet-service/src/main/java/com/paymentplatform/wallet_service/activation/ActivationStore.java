package com.paymentplatform.wallet_service.activation;
import com.paymentplatform.wallet_service.api.*;import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.ObjectMapper;import java.time.*;import java.util.*;
@Service public class ActivationStore {
 private final JdbcTemplate jdbc;private final ObjectMapper json;public ActivationStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public record Wallet(UUID id,UUID customer,String currency,String status,UUID account){}
 public record Activation(UUID walletId,UUID customerId,String currency,String status,UUID ledgerAccountId,Instant activatedAt){}
 public record Intent(Wallet wallet,Activation completed){}
 public record Completion(Activation response,boolean replayed){}
 public Wallet wallet(String tenant,UUID id){return jdbc.query("SELECT id,customer_id,currency,status,ledger_account_id FROM wallets WHERE integrator_id=? AND id=?",(row,n)->new Wallet(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),row.getString(4),row.getObject(5,UUID.class)),tenant,id).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"WALLET_NOT_FOUND"));}
 @Transactional(timeout=10) public Intent begin(String tenant,String actor,UUID id,String key){
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)row->{},tenant.length()+":"+tenant+key);
  var wallet=wallet(tenant,id);
  var inserted=jdbc.query("INSERT INTO wallet_activations(integrator_id,wallet_id,idempotency_key,actor_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING RETURNING wallet_id",(row,n)->row.getObject(1,UUID.class),tenant,id,key,actor);
  var rows=jdbc.query("SELECT wallet_id,actor_id,snapshot::text FROM wallet_activations WHERE integrator_id=? AND idempotency_key=?",(row,n)->new Saved(row.getObject(1,UUID.class),row.getString(2),row.getString(3)),tenant,key);
  if(rows.isEmpty()||!rows.getFirst().wallet().equals(id)||!rows.getFirst().actor().equals(actor))throw error(HttpStatus.CONFLICT,"ACTIVATION_KEY_OR_ACTOR_CONFLICT");
  var old=rows.getFirst();return new Intent(wallet,old.snapshot()==null?null:json.readValue(old.snapshot(),Activation.class));
 }
 @Transactional(timeout=10) public Completion finish(String tenant,String actor,UUID id,String key,UUID account){
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(org.springframework.jdbc.core.RowCallbackHandler)row->{},tenant.length()+":"+tenant+key);
  jdbc.query("SELECT id FROM wallets WHERE integrator_id=? AND id=? FOR UPDATE",(row,n)->row.getObject(1,UUID.class),tenant,id);
  var intent=begin(tenant,actor,id,key);if(intent.completed()!=null)return new Completion(intent.completed(),true);if(!intent.wallet().status().equals("PENDING"))throw error(HttpStatus.CONFLICT,"WALLET_STATE_CONFLICT");
  Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();var result=new Activation(id,intent.wallet().customer(),intent.wallet().currency(),"ACTIVE",account,now);
  jdbc.update("UPDATE wallets SET status='ACTIVE',ledger_account_id=?,activated_at=? WHERE integrator_id=? AND id=?",account,OffsetDateTime.ofInstant(now,ZoneOffset.UTC),tenant,id);
  jdbc.update("UPDATE wallet_activations SET snapshot=?::jsonb WHERE integrator_id=? AND wallet_id=?",json.writeValueAsString(result),tenant,id);return new Completion(result,false);
 }
 private record Saved(UUID wallet,String actor,String snapshot){}
 private WalletApiException error(HttpStatus status,String code){return new WalletApiException(status,code,"The wallet activation cannot be accepted.");}
}
