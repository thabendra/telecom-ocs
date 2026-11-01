package com.thabendra.telecom.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CA-3: keys stored together must not expire together. */
class ReadCacheJitterTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void everyLifeStaysInsideTheRangeAndTheyDiffer() {
        Set<Duration> lives = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            Duration life = ReadCache.withJitter(TEN_MINUTES, 20);
            assertThat(life).isBetween(Duration.ofMinutes(8), Duration.ofMinutes(12).plusMillis(1));
            lives.add(life);
        }

        assertThat(lives).hasSizeGreaterThan(900);
    }

    @Test
    void noJitterMeansExactlyTheTtl() {
        assertThat(ReadCache.withJitter(TEN_MINUTES, 0)).isEqualTo(TEN_MINUTES);
    }
}
