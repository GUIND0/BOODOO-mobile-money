package com.paymentplatform.reconciliation_service.domain;
import com.paymentplatform.reconciliation_service.client.DependencyClient;import org.springframework.stereotype.Service;import java.time.Instant;
@Service public class ReconciliationWorkflow {
 private final DependencyClient client;private final ReportStore store;public ReconciliationWorkflow(DependencyClient client,ReportStore store){this.client=client;this.store=store;}
 public ReportStore.Result check(String tenant,String actor,String key,String bearer){
  var prior=store.key(tenant,actor,key);if(prior.isPresent())return new ReportStore.Result(prior.get(),true);
  var reply=client.get("ledger","/internal/v1/reconciliation/snapshot",bearer);if(reply.status()!=200)throw DependencyClient.unavailable();
  long count=DependencyClient.integer(reply.body(),"journalCount"),unbalanced=DependencyClient.integer(reply.body(),"unbalancedJournals"),invalid=DependencyClient.integer(reply.body(),"invalidBalances");
  Instant checked;try{checked=Instant.parse(DependencyClient.text(reply.body(),"checkedAt"));}catch(Exception e){throw DependencyClient.unavailable();}
  Instant now=Instant.now();if(count<0||unbalanced<0||unbalanced>count||invalid<0||checked.isBefore(now.minusSeconds(60))||checked.isAfter(now.plusSeconds(30)))throw DependencyClient.unavailable();
  return store.save(tenant,actor,key,count,unbalanced,invalid,checked);
 }
}
