package com.paymentplatform.customer_service.api;

public class CustomerApiException extends RuntimeException {
    private final String code;

    public CustomerApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
