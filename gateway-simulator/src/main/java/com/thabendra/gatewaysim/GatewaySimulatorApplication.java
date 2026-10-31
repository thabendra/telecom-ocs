package com.thabendra.gatewaysim;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;

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

    private static final Logger log = LoggerFactory.getLogger(GatewaySimulatorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(GatewaySimulatorApplication.class, args);
    }

    // a command line app has no actuator, so keep the counters in memory and print them at the end
    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    RetryingCaller retryingCaller(SimulatorProperties props, MeterRegistry metrics) {
        return new RetryingCaller(props.retry(), duration -> Thread.sleep(duration), Clock.systemUTC(), metrics);
    }

    // One HTTP client for the backend and the token server: per-try timeout (RT-8) and, with
    // simulator.tls-bundle, this gateway's certificate (SE-1, and the token server's tls_client_auth)
    @Bean
    ClientHttpRequestFactory gatewayHttp(SimulatorProperties props, SslBundles sslBundles,
                                         @Value("${simulator.tls-bundle:}") String tlsBundle) {
        SslBundle tls = tlsBundle.isBlank() ? null : sslBundles.getBundle(tlsBundle);
        return GatewayHttp.requestFactory(props.retry().perTryTimeout(), tls);
    }

    // SE-2: client credentials from the token server; without simulator.token.url no token is sent
    @Bean
    AccessTokens accessTokens(ClientHttpRequestFactory gatewayHttp, SimulatorProperties props,
                              @Value("${simulator.token.url:}") String tokenUrl,
                              @Value("${simulator.token.scope:charging:write}") String scope) {
        if (tokenUrl.isBlank()) {
            return AccessTokens.NONE;
        }
        return new TokenClient(RestClient.builder().requestFactory(gatewayHttp).build(), tokenUrl, props.clientId(),
                scope, Clock.systemUTC());
    }

    // REST or SOAP from simulator.protocol, wrapped with the retry policy; the simulator only sees the interface
    @Bean
    ChargingClient chargingClient(RestClient.Builder builder, ClientHttpRequestFactory gatewayHttp,
                                  SimulatorProperties props, RetryingCaller caller, AccessTokens accessTokens) {
        return ChargingClients.create(props, builder.requestFactory(gatewayHttp), caller, accessTokens);
    }

    @Bean
    GatewaySimulator gatewaySimulator(ChargingClient client, SimulatorProperties props) {
        return new GatewaySimulator(client, props);
    }

    @Bean
    @ConditionalOnProperty(name = "simulator.run-on-startup", havingValue = "true", matchIfMissing = true)
    CommandLineRunner runSession(GatewaySimulator simulator, MeterRegistry metrics) {
        return args -> {
            simulator.run();
            // RT-10
            for (String name : List.of(RetryingCaller.RETRIES, RetryingCaller.SUCCEEDED_AFTER_RETRY,
                    RetryingCaller.EXHAUSTED, RetryingCaller.CIRCUIT_REJECTED)) {
                log.info("METRIC {} = {}", name, (long) metrics.counter(name).count());
            }
        };
    }
}
