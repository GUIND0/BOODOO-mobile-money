package com.paymentplatform.api_gateway.api;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
@RestControllerAdvice public class ApiExceptionHandler {
 @ExceptionHandler(ApiException.class) ProblemDetail business(ApiException error){var problem=ProblemDetail.forStatusAndDetail(error.status(),error.getMessage());problem.setProperty("code",error.code());return problem;}
 @ExceptionHandler(Exception.class) ProblemDetail unexpected(Exception error){var problem=ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,"The gateway operation could not complete.");problem.setProperty("code","GATEWAY_UNAVAILABLE");return problem;}
}
