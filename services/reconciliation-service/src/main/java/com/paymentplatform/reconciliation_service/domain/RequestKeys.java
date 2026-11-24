package com.paymentplatform.reconciliation_service.domain;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
public final class RequestKeys {
 private RequestKeys(){}
 public static String hash(String canonical,String actor){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((canonical+"\n"+actor).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
