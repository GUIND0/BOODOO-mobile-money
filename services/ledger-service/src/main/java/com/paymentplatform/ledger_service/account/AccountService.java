package com.paymentplatform.ledger_service.account;
import com.paymentplatform.ledger_service.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
@Service
public class AccountService {
    private final AccountRepository accounts;
    private final ObjectMapper json;
    public AccountService(AccountRepository accounts, ObjectMapper json) { this.accounts = accounts; this.json = json; }
    @Transactional(timeout=10)
    public Creation create(String tenant, String key, CreateAccountRequest request, String actor) {
        String fingerprint = fingerprint(request,actor);
        accounts.lock(tenant,key);
        var previous = accounts.findRequest(tenant,key);
        if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint)) throw new AccountApiException(HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT","This key was used with different account details or a different actor.");
            return new Creation(json.readValue(previous.get().snapshot(),AccountResponse.class),true);
        }
        var response = accounts.insert(tenant,request,actor).orElseThrow(()->new AccountApiException(HttpStatus.CONFLICT,
                "ACCOUNT_ALREADY_EXISTS","An account already exists for this code and currency."));
        accounts.save(tenant,key,fingerprint,response,json.writeValueAsString(response));
        return new Creation(response,false);
    }
    public AccountResponse get(String tenant, UUID id) {
        return accounts.find(tenant,id).orElseThrow(()->new AccountApiException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Account not found for this integrator."));
    }
    private String fingerprint(CreateAccountRequest request, String actor) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value:List.of(request.code(),request.currency(),request.type().name(),actor)) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("Required digest unavailable",error); }
    }
    public record Creation(AccountResponse response, boolean replayed) { }
}
