package com.paymentplatform.transfer_service.domain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
@Configuration @EnableScheduling @ConditionalOnProperty(name="boodoo.recovery.enabled",havingValue="true")
public class RecoveryWorker {
 private final TransferStore store;private final TransferWorkflow workflow;
 public RecoveryWorker(TransferStore store,TransferWorkflow workflow){this.store=store;this.workflow=workflow;}
 @Scheduled(fixedDelayString="${boodoo.recovery.delay-ms:15000}") public void recover(){
  for(var work:store.work())try{workflow.recover(work.tenant(),work.id());}catch(RuntimeException ignored){/* Next bounded scan retries persisted intent. No token or financial details in logs. */}
 }
}
