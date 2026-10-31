package com.thabendra.telecom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Telecom Online Charging System: real-time data charging for network gateways over REST and SOAP. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TelecomOcsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelecomOcsApplication.class, args);
    }
}
