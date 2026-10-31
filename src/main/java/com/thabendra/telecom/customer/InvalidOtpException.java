package com.thabendra.telecom.customer;

/** The login code was wrong, used already, expired, or tried too often (401). One message for all. */
public class InvalidOtpException extends RuntimeException {

    public InvalidOtpException() {
        super("The code is wrong or expired. Ask for a new code.");
    }
}
