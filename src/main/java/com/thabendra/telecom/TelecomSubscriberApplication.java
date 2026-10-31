package com.thabendra.telecom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TelecomSubscriberApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelecomSubscriberApplication.class, args);
    }
}
