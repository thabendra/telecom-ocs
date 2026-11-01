package com.thabendra.tokenserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OAuth2 token server for the gateways (SE-2). Run with
 * <pre>
 *   mvn -f token-server/pom.xml spring-boot:run
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TokenServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenServerApplication.class, args);
    }
}
