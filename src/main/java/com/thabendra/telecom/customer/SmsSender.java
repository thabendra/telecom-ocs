package com.thabendra.telecom.customer;

/**
 * Sends a text message to a subscriber. The login code (CU-1) goes out through this, so a real SMS
 * provider (an SMPP link or an SMS gateway API) can replace {@link LoggingSmsSender} without touching
 * the login logic.
 */
public interface SmsSender {

    void send(String msisdn, String text);
}
