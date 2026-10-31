package com.thabendra.telecom.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DEV ONLY: "sends" the SMS by writing it to the log, so a login code can be read from the console.
 * Never use this in production: anyone who can read the logs could log in as any customer.
 */
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String msisdn, String text) {
        log.info("SMS to {}: {}", msisdn, text);
    }
}
