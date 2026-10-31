package com.thabendra.telecom.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The in-memory token bucket (RD-6), the same algorithm as redis/token-bucket.lua. */
class LocalRateLimiterTest {

    private static final RateLimitProperties.Limit FIVE_PER_SECOND = new RateLimitProperties.Limit(5, 5);

    private final MutableClock clock = new MutableClock();
    private final LocalRateLimiter limiter = new LocalRateLimiter(clock);

    @Test
    void fullBucketAllowsABurstThenRefuses() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("gw", FIVE_PER_SECOND).allowed()).isTrue();
        }

        Decision refused = limiter.tryAcquire("gw", FIVE_PER_SECOND);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.waitMs()).isEqualTo(200);          // 5 per second = one token every 200 ms
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);  // whole seconds, rounded up
    }

    @Test
    void tokensComeBackAtTheRefillRate() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("gw", FIVE_PER_SECOND);
        }

        clock.advance(Duration.ofMillis(199));
        assertThat(limiter.tryAcquire("gw", FIVE_PER_SECOND).allowed()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("gw", FIVE_PER_SECOND).allowed()).isTrue();
    }

    @Test
    void anIdleBucketFillsOnlyUpToCapacity() {
        limiter.tryAcquire("gw", FIVE_PER_SECOND);
        clock.advance(Duration.ofHours(1));

        int allowed = 0;
        while (limiter.tryAcquire("gw", FIVE_PER_SECOND).allowed()) {
            allowed++;
        }

        assertThat(allowed).isEqualTo(5);   // not 18,000: a long pause never saves up a huge burst
    }

    @Test
    void eachGatewayHasItsOwnBucket() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("pgw-colombo-01", FIVE_PER_SECOND);
        }

        assertThat(limiter.tryAcquire("pgw-colombo-01", FIVE_PER_SECOND).allowed()).isFalse();
        assertThat(limiter.tryAcquire("pgw-kandy-01", FIVE_PER_SECOND).allowed()).isTrue();
    }

    /** A clock that only moves when the test says so. */
    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-31T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
