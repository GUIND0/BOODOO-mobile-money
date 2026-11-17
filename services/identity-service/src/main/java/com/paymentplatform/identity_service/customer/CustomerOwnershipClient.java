package com.paymentplatform.identity_service.customer;

import com.paymentplatform.identity_service.api.PinApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class CustomerOwnershipClient {
    private final CustomerClientProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public CustomerOwnershipClient(CustomerClientProperties properties) { this.properties = properties; }

    public void requireOwnership(UUID customer, String bearerToken) {
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve("/internal/v1/customers/" + customer + "/ownership"))
                .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + bearerToken).GET().build();
        int status;
        try { status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (IOException error) { throw unavailable(); }
        if (status == 204) return;
        if (status == 404) throw new PinApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found for this integrator.");
        if (status == 403) throw new PinApiException(HttpStatus.FORBIDDEN, "OWNERSHIP_FORBIDDEN", "Customer ownership lookup is not authorized.");
        throw unavailable();
    }

    private PinApiException unavailable() {
        return new PinApiException(HttpStatus.SERVICE_UNAVAILABLE, "OWNERSHIP_UNAVAILABLE",
                "Customer ownership could not be verified. No PIN was created.");
    }
}
