package com.paymentplatform.identity_service.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class PinExceptionHandler {
    @ExceptionHandler(PinApiException.class)
    ResponseEntity<ProblemDetail> business(PinApiException error) {
        var response = ResponseEntity.status(error.status());
        if (error.status() == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "900");
        return response.body(problem(error.status(), error.code(), error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid(Exception error) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Provide a valid customer UUID, a six-digit PIN string and the headers required by this endpoint.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupported(HttpMediaTypeNotSupportedException error) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail unavailable(DataAccessException error, HttpServletRequest request) {
        if (request.getRequestURI().endsWith("/pin/verify")) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "PIN_VERIFICATION_UNAVAILABLE",
                    "PIN verification is temporarily unavailable. Retry later.");
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PIN_CREATION_UNAVAILABLE",
                "PIN creation is temporarily unavailable. Retry the same request with the same Idempotency-Key.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
