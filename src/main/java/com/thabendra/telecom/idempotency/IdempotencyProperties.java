package com.thabendra.telecom.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * telecom.idempotency.* in application.yml.
 *
 * @param paths          REST write endpoints that must carry an Idempotency-Key header (Ant patterns)
 * @param soapPath       where the SOAP API is served (Ant pattern); all its operations share this URL
 * @param soapOperations SOAP operations (first element in soapenv:Body) that must carry the header
 * @param resultTtl      how long a finished result is kept so retries get the same answer
 * @param lockTtl        how long an in-progress lock lives if the server dies mid-request
 */
@ConfigurationProperties("telecom.idempotency")
public record IdempotencyProperties(List<String> paths, String soapPath, List<String> soapOperations,
                                    Duration resultTtl, Duration lockTtl) {
}
