package com.paymentplatform.ledger_service.journal;

import com.paymentplatform.ledger_service.api.AccountApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.*;

/** Signed grants constrain immutable account types; request headers never grant posting rights. */
@Component
public class PostingPolicy {
    private final JdbcTemplate jdbc;
    public PostingPolicy(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void require(Jwt jwt, JournalRequest request) {
        String scope = jwt.getClaimAsString("scope");
        Set<String> grants = scope == null ? Set.of() : new HashSet<>(Arrays.asList(scope.split(" ")));
        if (grants.contains("ledger:post:adjustment")) return;
        String tenant = jwt.getClaimAsString("integrator_id");
        var types = new HashMap<UUID,String>();
        for (var entry : request.entries()) {
            var found = jdbc.query("SELECT account_type FROM ledger_accounts WHERE integrator_id=? AND id=?", (r,n)->r.getString(1), tenant, entry.accountId());
            if (found.isEmpty()) throw new AccountApiException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Account not found.");
            types.put(entry.accountId(),found.getFirst());
        }
        long liabilityDebits=count(request,types,"LIABILITY",JournalRequest.Side.DEBIT);
        long liabilityCredits=count(request,types,"LIABILITY",JournalRequest.Side.CREDIT);
        long incomeCredits=count(request,types,"INCOME",JournalRequest.Side.CREDIT);
        boolean transfer=liabilityDebits==1 && liabilityCredits==1 && incomeCredits<=1 && request.entries().size()==2+incomeCredits;
        boolean settlement=request.entries().size()==2 && ((count(request,types,"ASSET",JournalRequest.Side.DEBIT)==1 && liabilityCredits==1)
                || (liabilityDebits==1 && count(request,types,"ASSET",JournalRequest.Side.CREDIT)==1));
        if ((grants.contains("ledger:post:transfer") && transfer) || (grants.contains("ledger:post:settlement") && settlement)) return;
        throw new AccountApiException(HttpStatus.FORBIDDEN,"POSTING_GRANT_REQUIRED","The signed posting grant does not authorize these account types and sides.");
    }
    private long count(JournalRequest r,Map<UUID,String> types,String type,JournalRequest.Side side) {
        return r.entries().stream().filter(e->type.equals(types.get(e.accountId())) && e.side()==side).count();
    }
}
