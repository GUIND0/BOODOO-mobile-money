package com.paymentplatform.transfer_service.domain;
import com.paymentplatform.transfer_service.api.ApiException;import com.paymentplatform.transfer_service.client.DependencyClient;import org.springframework.http.HttpStatus;import org.springframework.stereotype.Service;import tools.jackson.databind.JsonNode;import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;
@Service public class TransferWorkflow {
 private final TransferStore store;private final DependencyClient client;private final ServiceCredentials credentials;
 public TransferWorkflow(TransferStore store,DependencyClient client,ServiceCredentials credentials){this.store=store;this.client=client;this.credentials=credentials;}
 public record Result(TransferStore.View response,boolean replayed){}
 private record Wallet(UUID id,UUID customer,UUID account){}
 public Result accept(String tenant,String actor,String key,TransferRequest request){var begin=store.begin(tenant,actor,key,request);return new Result(store.view(execute(tenant,begin.row().id(),request.pin())),begin.replayed());}
 public TransferStore.Row resume(String tenant,UUID id){return execute(tenant,id,null);}
 private TransferStore.Row execute(String tenant,UUID id,String pin){
  var row=store.get(tenant,id);if(row.state().equals("COMPLETED")||row.state().equals("REJECTED"))return row;
  if(row.state().equals("CREATED")&&pin==null)return row;UUID lease=store.claim(tenant,id);if(lease==null)return store.get(tenant,id);
  try{
   String token=credentials.bearer(tenant);row=store.get(tenant,id);
   if(row.state().equals("CREATED")){
    var sender=wallet(row.sender(),token);var recipient=wallet(row.recipient(),token);if(sender.account().equals(recipient.account()))throw reject("SAME_ACCOUNT");
    var quote=client.post("pricing","/internal/v1/quotes",Map.of("reference",id,"amount",row.amount(),"currency","XOF"),"quote-"+id,token);require(quote,201);
    if(!id.equals(DependencyClient.uuid(quote.body(),"reference"))||DependencyClient.integer(quote.body(),"amount")!=row.amount()||!"XOF".equals(DependencyClient.text(quote.body(),"currency")))throw DependencyClient.unavailable();
    long fee=DependencyClient.integer(quote.body(),"fee"),total=DependencyClient.integer(quote.body(),"totalDebit");if(fee<0||Math.addExact(row.amount(),fee)!=total)throw DependencyClient.unavailable();if(fee>row.maxFee())throw reject("FEE_LIMIT_EXCEEDED");
    Instant quoteExpiry=expiry(quote.body(),"expiresAt");UUID feeAccount=null;
    if(fee>0){feeAccount=credentials.feeAccount(tenant);var account=client.get("ledger","/internal/v1/accounts/"+feeAccount,token);require(account,200);if(!feeAccount.equals(DependencyClient.uuid(account.body(),"id"))||!"INCOME".equals(DependencyClient.text(account.body(),"type"))||!"CREDIT".equals(DependencyClient.text(account.body(),"normalBalance"))||!"XOF".equals(DependencyClient.text(account.body(),"currency"))||feeAccount.equals(sender.account())||feeAccount.equals(recipient.account()))throw DependencyClient.unavailable();}
    var verification=client.post("identity","/v1/customers/"+sender.customer()+"/pin/verify",Map.of("pin",pin),null,token);if(verification.status()==422||verification.status()==429)throw reject("PIN_VERIFICATION_REJECTED");require(verification,204);
    var context=new TransferStore.Context(sender.customer(),recipient.customer(),sender.account(),recipient.account(),feeAccount,DependencyClient.uuid(quote.body(),"id"),fee,total,quoteExpiry,null,null,null,null);
    row=store.save(tenant,id,lease,"AUTHORIZED",context,null,null);
   }
   if(row.state().equals("AUTHORIZED")){
    ensureFuture(row.context().quoteExpires());var risk=risk(row,token);row=store.save(tenant,id,lease,"RESERVING",risk,null,null);
   }
   if(row.state().equals("RESERVING")){
    ensureFuture(row.context().quoteExpires());var result=client.post("limits","/internal/v1/reservations",Map.of("reference",id,"customerId",row.context().senderCustomer(),"amount",row.context().total(),"currency","XOF"),"limit-"+id,token);require(result,201);
    if(!id.equals(DependencyClient.uuid(result.body(),"reference"))||!row.context().senderCustomer().equals(DependencyClient.uuid(result.body(),"customerId"))||DependencyClient.integer(result.body(),"amount")!=row.context().total()||!"XOF".equals(DependencyClient.text(result.body(),"currency"))||!"RESERVED".equals(DependencyClient.text(result.body(),"status")))throw DependencyClient.unavailable();
    var context=row.context().reservation(DependencyClient.uuid(result.body(),"id"),expiry(result.body(),"expiresAt"));row=store.save(tenant,id,lease,"RESERVED",context,null,null);
   }
   if(row.state().equals("RESERVED")){
    ensureFuture(row.context().quoteExpires());ensureFuture(row.context().reservationExpires());verifyWallets(row,token);var context=risk(row,token);row=store.save(tenant,id,lease,"POSTING",context,null,null);
   }
   if(row.state().equals("POSTING")){
    var reply=client.get("ledger","/internal/v1/journals/by-reference?reference="+id,token);
    if(reply.status()==404){ensureFuture(row.context().quoteExpires());ensureFuture(row.context().reservationExpires());ensureFuture(row.context().riskExpires());verifyWallets(row,token);reply=client.post("ledger","/internal/v1/journals",Map.of("reference",id,"entries",entries(row)),"posting-"+id,token);require(reply,201);}else require(reply,200);
    verifyReceipt(row,reply.body());UUID journal=DependencyClient.uuid(reply.body(),"id");row=store.save(tenant,id,lease,"POSTED",row.context(),journal,null);
   }
   if(row.state().equals("POSTED")){
    var confirmed=client.post("limits","/internal/v1/reservations/"+row.context().reservationId()+"/confirm",null,null,token);require(confirmed,200);if(!row.context().reservationId().equals(DependencyClient.uuid(confirmed.body(),"id"))||!"CONFIRMED".equals(DependencyClient.text(confirmed.body(),"status")))throw DependencyClient.unavailable();
    var audit=client.post("audit","/internal/v1/events",Map.of("id",eventId(id,"audit"),"resourceId",id,"type","TRANSFER_POSTED","reasonCode","POSTED"),null,token);require(audit,201);
    for(var notification:List.of(Map.of("id",eventId(id,"sender"),"customerId",row.context().senderCustomer(),"reference",id,"template","TRANSFER_POSTED"),Map.of("id",eventId(id,"recipient"),"customerId",row.context().recipientCustomer(),"reference",id,"template","TRANSFER_POSTED"))){var queued=client.post("notification","/internal/v1/notifications",notification,null,token);require(queued,201);}
    row=store.save(tenant,id,lease,"COMPLETED",row.context(),row.journal(),null);
   }
   return row;
  }catch(ApiException error){row=store.get(tenant,id);if(!error.code().equals("LEASE_LOST"))try{boolean terminal=(row.state().equals("CREATED")||row.state().equals("AUTHORIZED"))&&error.status()==HttpStatus.UNPROCESSABLE_CONTENT;row=store.save(tenant,id,lease,terminal?"REJECTED":row.state(),row.context(),row.journal(),error.code());}catch(ApiException lost){row=store.get(tenant,id);}return row;
  }catch(ArithmeticException|IllegalArgumentException error){row=store.get(tenant,id);try{return store.save(tenant,id,lease,row.state(),row.context(),row.journal(),"DEPENDENCY_UNAVAILABLE");}catch(ApiException lost){return store.get(tenant,id);}}
  finally{store.release(tenant,id,lease);}
 }
 private Wallet wallet(UUID id,String token){var reply=client.get("wallet","/internal/v1/wallets/"+id+"/operational-state",token);if(reply.status()==404)throw reject("WALLET_NOT_FOUND");require(reply,200);if(!id.equals(DependencyClient.uuid(reply.body(),"walletId"))||!"XOF".equals(DependencyClient.text(reply.body(),"currency")))throw DependencyClient.unavailable();
  Instant checked=parse(DependencyClient.text(reply.body(),"checkedAt"));if(checked.isBefore(Instant.now().minusSeconds(30))||checked.isAfter(Instant.now().plusSeconds(30)))throw DependencyClient.unavailable();if(!"ACTIVE".equals(DependencyClient.text(reply.body(),"status"))||!DependencyClient.bool(reply.body(),"eligible"))throw reject("WALLET_NOT_ELIGIBLE");return new Wallet(id,DependencyClient.uuid(reply.body(),"customerId"),DependencyClient.uuid(reply.body(),"ledgerAccountId"));}
 private void verifyWallets(TransferStore.Row row,String token){var sender=wallet(row.sender(),token);var recipient=wallet(row.recipient(),token);if(!sender.customer().equals(row.context().senderCustomer())||!recipient.customer().equals(row.context().recipientCustomer())||!sender.account().equals(row.context().senderAccount())||!recipient.account().equals(row.context().recipientAccount()))throw DependencyClient.unavailable();}
 private TransferStore.Context risk(TransferStore.Row row,String token){UUID assessment=UUID.randomUUID();var reply=client.post("risk","/internal/v1/evaluations",Map.of("id",assessment,"reference",row.id(),"customerId",row.context().senderCustomer(),"amount",row.context().total(),"currency","XOF"),null,token);require(reply,201);
  if(!assessment.equals(DependencyClient.uuid(reply.body(),"id"))||!row.id().equals(DependencyClient.uuid(reply.body(),"reference"))||!row.context().senderCustomer().equals(DependencyClient.uuid(reply.body(),"customerId"))||DependencyClient.integer(reply.body(),"amount")!=row.context().total()||!"XOF".equals(DependencyClient.text(reply.body(),"currency")))throw DependencyClient.unavailable();if(!"ALLOW".equals(DependencyClient.text(reply.body(),"outcome")))throw reject("RISK_NOT_ALLOWED");return row.context().risk(assessment,expiry(reply.body(),"expiresAt"));}
 private List<Map<String,Object>> entries(TransferStore.Row row){var context=row.context();var entries=new ArrayList<Map<String,Object>>();entries.add(Map.of("accountId",context.senderAccount(),"side","DEBIT","amount",context.total()));entries.add(Map.of("accountId",context.recipientAccount(),"side","CREDIT","amount",row.amount()));if(context.fee()>0)entries.add(Map.of("accountId",context.feeAccount(),"side","CREDIT","amount",context.fee()));return entries;}
 private void verifyReceipt(TransferStore.Row row,JsonNode receipt){if(!row.id().equals(DependencyClient.uuid(receipt,"reference"))||!"XOF".equals(DependencyClient.text(receipt,"currency")))throw DependencyClient.unavailable();var actual=receipt.get("entries");if(actual==null||!actual.isArray()||actual.size()!=entries(row).size())throw DependencyClient.unavailable();var expected=new HashSet<String>();for(var entry:entries(row))expected.add(entry.get("accountId")+":"+entry.get("side")+":"+entry.get("amount"));for(var entry:actual)if(!expected.remove(DependencyClient.uuid(entry,"accountId")+":"+DependencyClient.text(entry,"side")+":"+DependencyClient.integer(entry,"amount")))throw DependencyClient.unavailable();if(!expected.isEmpty())throw DependencyClient.unavailable();}
 private UUID eventId(UUID transfer,String kind){return UUID.nameUUIDFromBytes((transfer+":"+kind).getBytes(StandardCharsets.UTF_8));}
 private void require(DependencyClient.Reply reply,int expected){if(reply.status()!=expected)throw DependencyClient.unavailable();}
 private Instant parse(String value){try{return Instant.parse(value);}catch(Exception error){throw DependencyClient.unavailable();}}
 private Instant expiry(JsonNode body,String field){Instant expiry=parse(DependencyClient.text(body,field));ensureFuture(expiry);return expiry;}
 private void ensureFuture(Instant expiry){if(expiry==null||!expiry.isAfter(Instant.now().plusSeconds(5)))throw reject("AUTHORIZATION_FACT_EXPIRED");}
 private ApiException reject(String code){return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,code,"This transfer cannot be authorized under the current facts.");}
}
