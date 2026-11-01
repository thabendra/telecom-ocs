package com.thabendra.telecom.rest;

import com.thabendra.telecom.customer.InvalidOtpException;
import com.thabendra.telecom.customer.OtpCooldownException;
import com.thabendra.telecom.resilience.RedisCircuitOpenException;
import com.thabendra.telecom.subscriber.BusinessRuleException;
import com.thabendra.telecom.subscriber.NotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Domain errors as RFC 9457 problem details; validation errors come from the base class. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail businessRule(BusinessRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    // the login codes live in Redis, so logging in has no fallback: "try again soon" (the others fall back)
    @ExceptionHandler(RedisCircuitOpenException.class)
    public ResponseEntity<ProblemDetail> redisOpen(RedisCircuitOpenException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "Login is unavailable for a moment, please try again soon"));
    }

    // CU-1: a wrong, used, expired or locked login code
    @ExceptionHandler(InvalidOtpException.class)
    public ProblemDetail invalidOtp(InvalidOtpException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(OtpCooldownException.class)
    public ResponseEntity<ProblemDetail> otpCooldown(OtpCooldownException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }
}
