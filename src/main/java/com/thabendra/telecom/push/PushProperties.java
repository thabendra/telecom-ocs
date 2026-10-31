package com.thabendra.telecom.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * telecom.push.* in application.yml.
 *
 * @param ackTimeout       how long the gateway has to ack a delivery before it is delivered again
 * @param maxBackoff       the wait before a redelivery doubles each time, up to this
 * @param maxAttempts      deliveries in total (1 + 3 retries, as in section I), then FAILED
 * @param batchSize        most commands one poll returns
 * @param payAsYouGoKbps   speed after a top-up unblocks a user (charging from the balance)
 */
@ConfigurationProperties("telecom.push")
public record PushProperties(Duration ackTimeout, Duration maxBackoff, int maxAttempts, int batchSize,
                             int payAsYouGoKbps) {

    /**
     * How long after delivery number {@code attempt} (1, 2, ...) to deliver again when there is no ack:
     * ackTimeout, 2x, 4x ... up to maxBackoff, plus up to 20 % jitter so many commands don't come back
     * at the same moment. Not full jitter: a redelivery before the ack timeout would only add duplicates.
     */
    public Duration redeliveryAfter(int attempt) {
        long base = ackTimeout.toMillis() << Math.min(Math.max(attempt - 1, 0), 20);
        long capped = Math.min(base, maxBackoff.toMillis());
        return Duration.ofMillis(capped + ThreadLocalRandom.current().nextLong(capped / 5 + 1));
    }
}
