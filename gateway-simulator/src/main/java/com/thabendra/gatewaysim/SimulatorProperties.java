package com.thabendra.gatewaysim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** simulator.* in application.yml; override any of them on the command line, e.g. --simulator.app=WHATSAPP */
@ConfigurationProperties("simulator")
public record SimulatorProperties(
        String backendUrl,
        String clientId,
        Protocol protocol,
        String msisdn,
        String app,
        long chunkMb,
        int maxUpdates,
        int usedPercent,
        long finalUsedMb,
        boolean replayEachRequest,
        RetryPolicy retry,
        FailOpenPolicy failOpen) {

    /** Which backend API the gateway calls; both carry the same charging operations. */
    public enum Protocol {
        REST,
        SOAP
    }
}
