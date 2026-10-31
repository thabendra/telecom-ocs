package com.thabendra.gatewaysim;

/**
 * The backend could not be reached in time: retries or the time budget are used up (RT-8) or the
 * circuit is open (RT-9). The gateway then applies its agreed failure mode (RL-6), here: let the
 * user's traffic through for a short time and charge it later.
 */
public class FailOpenException extends RuntimeException {

    public FailOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
