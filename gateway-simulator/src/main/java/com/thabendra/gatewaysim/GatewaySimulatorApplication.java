package com.thabendra.gatewaysim;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Runs one simulated gateway charging session against the backend and exits.
 * <pre>
 *   mvn -f gateway-simulator/pom.xml spring-boot:run
 *   mvn -f gateway-simulator/pom.xml spring-boot:run -Dspring-boot.run.arguments="--simulator.app=WHATSAPP"
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class GatewaySimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewaySimulatorApplication.class, args);
    }

    @Bean
    ChargingClient chargingClient(RestClient.Builder builder, SimulatorProperties props) {
        return new ChargingClient(builder, props.backendUrl(), props.clientId());
    }

    @Bean
    GatewaySimulator gatewaySimulator(ChargingClient client, SimulatorProperties props) {
        return new GatewaySimulator(client, props);
    }

    @Bean
    @ConditionalOnProperty(name = "simulator.run-on-startup", havingValue = "true", matchIfMissing = true)
    CommandLineRunner runSession(GatewaySimulator simulator) {
        return args -> simulator.run();
    }
}
