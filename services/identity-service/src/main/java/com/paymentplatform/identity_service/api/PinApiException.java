package com.paymentplatform.identity_service.api;

import org.springframework.http.HttpStatus;

public class PinApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public PinApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
