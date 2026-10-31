package com.thabendra.gatewaysim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** simulator.* in application.yml; override any of them on the command line, e.g. --simulator.app=WHATSAPP */
@ConfigurationProperties("simulator")
public record SimulatorProperties(
        String backendUrl,
        String clientId,
        String msisdn,
        String app,
        long chunkMb,
        int maxUpdates,
        int usedPercent,
        long finalUsedMb,
        boolean replayEachRequest) {
}
