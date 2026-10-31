package com.thabendra.telecom.push;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** PU-4: the wait before a command is delivered again grows per delivery, with a little jitter. */
class PushPropertiesTest {

    private final PushProperties push = new PushProperties(Duration.ofSeconds(10), Duration.ofSeconds(60), 4, 50, 1024);

    @RepeatedTest(20)
    void waitDoublesUpToTheCapWithAtMostTwentyPercentJitter() {
        assertThat(push.redeliveryAfter(1)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(12));
        assertThat(push.redeliveryAfter(2)).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(24));
        assertThat(push.redeliveryAfter(3)).isBetween(Duration.ofSeconds(40), Duration.ofSeconds(48));
        assertThat(push.redeliveryAfter(4)).isBetween(Duration.ofSeconds(60), Duration.ofSeconds(72));   // capped
    }

    @Test
    void neverShorterThanTheAckTimeout() {   // a redelivery before the gateway could ack only adds duplicates
        for (int attempt = 0; attempt < 30; attempt++) {
            assertThat(push.redeliveryAfter(attempt)).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
        }
    }
}
