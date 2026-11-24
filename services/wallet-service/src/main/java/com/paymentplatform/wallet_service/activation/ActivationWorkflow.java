package com.paymentplatform.wallet_service.activation;
import com.paymentplatform.wallet_service.api.WalletApiException;import com.paymentplatform.wallet_service.client.DependencyClient;import org.springframework.http.HttpStatus;import org.springframework.stereotype.Service;import java.time.Instant;import java.util.*;
@Service public class ActivationWorkflow {
 private final ActivationStore store;private final DependencyClient client;public ActivationWorkflow(ActivationStore store,DependencyClient client){this.store=store;this.client=client;}
 public record Result(ActivationStore.Activation response,boolean replayed){}
 public record Eligibility(UUID walletId,UUID customerId,String currency,String status,UUID ledgerAccountId,boolean eligible,Instant checkedAt){}
 public Result activate(String tenant,String actor,UUID id,String key,String token){
  var intent=store.begin(tenant,actor,id,key);if(intent.completed()!=null)return new Result(intent.completed(),true);var wallet=intent.wallet();requireFacts(wallet,token);
  String code="W_"+id.toString().replace("-","").toUpperCase(Locale.ROOT);
  var reply=client.post("ledger","/internal/v1/accounts",Map.of("code",code,"currency",wallet.currency(),"type","LIABILITY"),"wallet-"+id,token);
  if(reply.status()!=201)throw DependencyClient.unavailable();UUID account=DependencyClient.uuid(reply.body(),"id");
  if(!code.equals(DependencyClient.text(reply.body(),"code"))||!wallet.currency().equals(DependencyClient.text(reply.body(),"currency"))||!"LIABILITY".equals(DependencyClient.text(reply.body(),"type"))||!"CREDIT".equals(DependencyClient.text(reply.body(),"normalBalance")))throw DependencyClient.unavailable();
  var balance=client.get("ledger","/internal/v1/accounts/"+account+"/balance",token);
  if(balance.status()!=200||!account.equals(DependencyClient.uuid(balance.body(),"accountId"))||!wallet.currency().equals(DependencyClient.text(balance.body(),"currency"))||!"CREDIT".equals(DependencyClient.text(balance.body(),"normalBalance")))throw DependencyClient.unavailable();
  if(DependencyClient.integer(balance.body(),"amountMinorUnits")!=0)throw conflict("ACTIVATION_ACCOUNT_NOT_EMPTY");
  requireFacts(wallet,token);var completion=store.finish(tenant,actor,id,key,account);return new Result(completion.response(),completion.replayed());
 }
 public Eligibility eligibility(String tenant,UUID id,String token){var wallet=store.wallet(tenant,id);boolean eligible=wallet.status().equals("ACTIVE")&&wallet.account()!=null;
  if(eligible)try{requireFacts(wallet,token);}catch(WalletApiException exception){if(exception.status()!=HttpStatus.CONFLICT)throw exception;eligible=false;}
  return new Eligibility(id,wallet.customer(),wallet.currency(),wallet.status(),wallet.account(),eligible,Instant.now());
 }
 private void requireFacts(ActivationStore.Wallet wallet,String token){
  var kyc=client.get("customer","/internal/v1/customers/"+wallet.customer()+"/kyc-approval",token);if(kyc.status()!=200||!wallet.customer().equals(DependencyClient.uuid(kyc.body(),"customerId")))throw DependencyClient.unavailable();
  Instant checked;try{checked=Instant.parse(DependencyClient.text(kyc.body(),"checkedAt"));}catch(Exception e){throw DependencyClient.unavailable();}
  if(checked.isBefore(Instant.now().minusSeconds(30))||checked.isAfter(Instant.now().plusSeconds(30)))throw DependencyClient.unavailable();
  if(!DependencyClient.bool(kyc.body(),"approvalCurrent")||!"APPROVED".equals(DependencyClient.text(kyc.body(),"kycStatus")))throw conflict("CURRENT_KYC_REQUIRED");
  var pin=client.get("identity","/v1/customers/"+wallet.customer()+"/pin",token);if(pin.status()!=200||!wallet.customer().equals(DependencyClient.uuid(pin.body(),"customerId")))throw DependencyClient.unavailable();
  if(!DependencyClient.bool(pin.body(),"configured"))throw conflict("PIN_CONFIGURATION_REQUIRED");
 }
 private WalletApiException conflict(String code){return new WalletApiException(HttpStatus.CONFLICT,code,"Current KYC approval, a configured PIN and an unused accounting account are required.");}
}
