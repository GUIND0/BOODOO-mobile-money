package com.paymentplatform.customer_service.api;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class CustomerExceptionHandler {

    @ExceptionHandler(CustomerApiException.class)
    ProblemDetail conflict(CustomerApiException exception) {
        return problem(HttpStatus.CONFLICT, exception.code(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ProblemDetail invalidRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Provide a valid customer profile and an Idempotency-Key of 1 to 128 permitted characters.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedContentType(HttpMediaTypeNotSupportedException exception) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type application/json.");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail unavailable(DataAccessException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "CUSTOMER_CREATION_UNAVAILABLE",
                "Creation is temporarily unavailable. Retry the same request with the same Idempotency-Key.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
