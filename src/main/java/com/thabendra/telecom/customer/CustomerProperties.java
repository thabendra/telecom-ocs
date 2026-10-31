package com.thabendra.telecom.customer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** telecom.customers.* in application.yml: the customer login (CU-1). */
@ConfigurationProperties("telecom.customers")
public record CustomerProperties(Otp otp, Token token) {

    /**
     * @param ttl         how long a code works
     * @param cooldown    the wait before the same number can ask for a new code
     * @param maxAttempts wrong codes before the code is deleted
     * @param hashSecret  HMAC key: Redis keeps HMAC(msisdn:code), never the code itself
     */
    public record Otp(Duration ttl, Duration cooldown, int maxAttempts, String hashSecret) {
    }

    /**
     * @param issuer   iss of the tokens this backend gives customers
     * @param audience aud of those tokens
     * @param secret   HS256 key, at least 32 bytes, shared by every backend copy
     * @param ttl      how long a token works
     */
    public record Token(String issuer, String audience, String secret, Duration ttl) {
    }
}
