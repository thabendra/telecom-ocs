package com.thabendra.telecom.monitoring;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The management port (OB-2) has no login: it is protected by the network instead (only the
 * monitoring subnet can reach it). The actuator endpoints exist only on that port, so this rule never
 * opens anything on the gateway or public port; without it the public API's "everything else is
 * closed" rule would refuse Prometheus.
 */
@Configuration
public class ManagementSecurity {

    @Bean
    @Order(0)
    SecurityFilterChain managementPort(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
