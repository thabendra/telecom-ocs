package com.thabendra.telecom.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Limits per gateway, overrides, and the local fallback while Redis is down (RD-6). */
class GatewayRateLimiterTest {

    // 6 burst, 3/s per gateway, at most 3 backend copies; pgw-kandy-01 has a smaller limit
    private final RateLimitProperties properties = new RateLimitProperties(6, 3, 3,
            Map.of("pgw-kandy-01", new RateLimitProperties.Limit(2, 1)), 10, Duration.ofMillis(50), 3);

    @Test
    void usesTheSharedRedisBucketWithTheGatewaysLimit() {
        RedisRateLimiter redis = mock(RedisRateLimiter.class);
        when(redis.tryAcquire(eq("pgw-colombo-01"), eq(new RateLimitProperties.Limit(6, 3))))
                .thenReturn(Decision.allow());
        when(redis.tryAcquire(eq("pgw-kandy-01"), eq(new RateLimitProperties.Limit(2, 1))))
                .thenReturn(new Decision(false, 400));
        GatewayRateLimiter limiter = new GatewayRateLimiter(redis, properties);

        assertThat(limiter.tryAcquire("pgw-colombo-01").allowed()).isTrue();
        assertThat(limiter.tryAcquire("pgw-kandy-01")).isEqualTo(new Decision(false, 400));
    }

    @Test
    void whenRedisIsDownEachCopyCountsLocallyWithItsShare() {
        RedisRateLimiter redis = mock(RedisRateLimiter.class);
        when(redis.tryAcquire(any(), any())).thenThrow(new QueryTimeoutException("Redis command timed out"));
        LocalRateLimiter local = new LocalRateLimiter(Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC));
        GatewayRateLimiter limiter = new GatewayRateLimiter(redis, local, properties);

        // 6 burst / 3 copies = 2 in this copy, so traffic goes on but all copies together stay at the limit
        assertThat(limiter.tryAcquire("pgw-colombo-01").allowed()).isTrue();
        assertThat(limiter.tryAcquire("pgw-colombo-01").allowed()).isTrue();
        assertThat(limiter.tryAcquire("pgw-colombo-01").allowed()).isFalse();
    }

    @Test
    void shareNeverDropsBelowOneToken() {
        assertThat(new RateLimitProperties.Limit(2, 1).share(10)).isEqualTo(new RateLimitProperties.Limit(1, 0.1));
    }

    @Test
    void retryAfterIsWholeSecondsRoundedUp() {
        assertThat(new Decision(false, 1).retryAfterSeconds()).isEqualTo(1);
        assertThat(new Decision(false, 1000).retryAfterSeconds()).isEqualTo(1);
        assertThat(new Decision(false, 1001).retryAfterSeconds()).isEqualTo(2);
    }
}
